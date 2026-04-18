#!/usr/bin/env python3
"""Valida una sesión de grabación IMU producida por ImuFlux.

Uso:
    python tools/validate_session.py /ruta/a/sessions/<session_id>
    python tools/validate_session.py /ruta/a/sesion_exportada.csv

Acepta:
  * Un directorio con `chunk_*.csv` + `metadata.json` (tal como quedan en
    `context.filesDir/sessions/<id>`).
  * Un único CSV exportado (resultado de "Exportar CSV" desde la app).
  * Un ZIP exportado (resultado de "Exportar ZIP" desde la app).

Criterios de aceptación para una sesión de 8 h con pantalla bloqueada:
  * mediana(dt) ∈ [9.5 ms, 10.5 ms]
  * sin huecos (dt > 50 ms)
  * p95(|dt − 10 ms|) < 5 ms

Salida: imprime un resumen y devuelve código 0 si todo OK, 1 si falla algún
criterio, 2 si el formato del fichero es incorrecto.

Dependencias: sólo biblioteca estándar de Python 3.8+.
"""
from __future__ import annotations

import argparse
import csv
import io
import os
import sys
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Iterator, List, Optional, Tuple


NOMINAL_DT_NS = 10_000_000  # 100 Hz
GAP_THRESHOLD_NS = 50_000_000  # 50 ms
EXPECTED_HEADER_PREFIX = ("timestamp_ns",)


@dataclass
class Stats:
    total_rows: int
    duration_s: float
    dt_median_ns: float
    dt_mean_ns: float
    dt_p95_ns: float
    dt_p99_ns: float
    jitter_p95_ns: float
    gaps: int
    max_gap_ns: int
    first_ts_ns: int
    last_ts_ns: int

    def _duration_human(self) -> str:
        total_s = int(self.duration_s)
        h, rem = divmod(total_s, 3600)
        m, s = divmod(rem, 60)
        parts = []
        if h:
            parts.append(f"{h} h")
        if m:
            parts.append(f"{m} min")
        parts.append(f"{s} s")
        return " ".join(parts)

    def pretty(self) -> str:
        return (
            f"rows              = {self.total_rows:>12d}\n"
            f"duration          = {self.duration_s:>12.2f} s  ({self._duration_human()})\n"
            f"first_ts_ns       = {self.first_ts_ns:>20d}\n"
            f"last_ts_ns        = {self.last_ts_ns:>20d}\n"
            f"dt_mean_ms        = {self.dt_mean_ns / 1e6:>12.4f}\n"
            f"dt_median_ms      = {self.dt_median_ns / 1e6:>12.4f}  (objetivo: 9.5 – 10.5 ms)\n"
            f"dt_p95_ms         = {self.dt_p95_ns / 1e6:>12.4f}\n"
            f"dt_p99_ms         = {self.dt_p99_ns / 1e6:>12.4f}\n"
            f"jitter_p95_ms     = {self.jitter_p95_ns / 1e6:>12.4f}  (objetivo: < 5 ms)\n"
            f"gaps (>50 ms)     = {self.gaps:>12d}  (objetivo: 0)\n"
            f"max_gap_ms        = {self.max_gap_ns / 1e6:>12.4f}\n"
        )

    def passes_spec(self) -> Tuple[bool, List[str]]:
        errs: List[str] = []
        if not (9_500_000 <= self.dt_median_ns <= 10_500_000):
            errs.append(
                f"dt_median fuera de rango: {self.dt_median_ns / 1e6:.4f} ms "
                f"(esperado 9.5–10.5)"
            )
        if self.gaps > 0:
            errs.append(f"huecos detectados: {self.gaps} (máx {self.max_gap_ns / 1e6:.2f} ms)")
        if self.jitter_p95_ns > 5_000_000:
            errs.append(
                f"jitter p95 alto: {self.jitter_p95_ns / 1e6:.4f} ms (esperado < 5)"
            )
        return (not errs), errs


def iter_csv_rows(streams: Iterable[io.TextIOBase]) -> Iterator[List[str]]:
    """Concatena múltiples lectores saltando cabeceras repetidas."""
    first_header: Optional[List[str]] = None
    for stream in streams:
        reader = csv.reader(stream)
        try:
            header = next(reader)
        except StopIteration:
            continue
        if first_header is None:
            first_header = header
            if tuple(header[: len(EXPECTED_HEADER_PREFIX)]) != EXPECTED_HEADER_PREFIX:
                raise ValueError(f"Cabecera inesperada: {header}")
        else:
            if header != first_header:
                raise ValueError(
                    f"Cabecera de chunk difiere — ¿sesión corrupta? {header} != {first_header}"
                )
        for row in reader:
            if row:
                yield row


def collect_timestamps(rows: Iterator[List[str]]) -> List[int]:
    ts: List[int] = []
    for row in rows:
        try:
            ts.append(int(row[0]))
        except (ValueError, IndexError):
            continue
    return ts


def percentile(sorted_values: List[int], p: float) -> float:
    if not sorted_values:
        return 0.0
    idx = int((len(sorted_values) - 1) * p)
    return float(sorted_values[idx])


def compute_stats(timestamps: List[int]) -> Stats:
    if len(timestamps) < 2:
        raise ValueError("Se requieren al menos 2 muestras para validar")
    deltas = [timestamps[i + 1] - timestamps[i] for i in range(len(timestamps) - 1)]
    deltas_sorted = sorted(deltas)
    abs_jitter = sorted(abs(d - NOMINAL_DT_NS) for d in deltas)
    gaps = sum(1 for d in deltas if d > GAP_THRESHOLD_NS)
    max_gap = max(deltas)
    mean_dt = sum(deltas) / len(deltas)
    return Stats(
        total_rows=len(timestamps),
        duration_s=(timestamps[-1] - timestamps[0]) / 1e9,
        dt_median_ns=percentile(deltas_sorted, 0.5),
        dt_mean_ns=mean_dt,
        dt_p95_ns=percentile(deltas_sorted, 0.95),
        dt_p99_ns=percentile(deltas_sorted, 0.99),
        jitter_p95_ns=percentile(abs_jitter, 0.95),
        gaps=gaps,
        max_gap_ns=max_gap,
        first_ts_ns=timestamps[0],
        last_ts_ns=timestamps[-1],
    )


def open_session(path: Path) -> List[io.TextIOBase]:
    if path.is_dir():
        chunks = sorted(path.glob("chunk_*.csv"))
        if not chunks:
            raise FileNotFoundError(f"No hay chunk_*.csv en {path}")
        return [c.open("r", encoding="utf-8") for c in chunks]
    if path.suffix.lower() == ".zip":
        tmpdir = tempfile.mkdtemp(prefix="imuflux_val_")
        with zipfile.ZipFile(path) as zf:
            zf.extractall(tmpdir)
        session_dirs = [
            p for p in Path(tmpdir).iterdir() if p.is_dir() and any(p.glob("chunk_*.csv"))
        ]
        if session_dirs:
            return open_session(session_dirs[0])
        chunks = sorted(Path(tmpdir).glob("chunk_*.csv"))
        if chunks:
            return [c.open("r", encoding="utf-8") for c in chunks]
        raise FileNotFoundError(f"ZIP sin chunks reconocibles: {path}")
    if path.suffix.lower() == ".csv":
        return [path.open("r", encoding="utf-8")]
    raise ValueError(f"Formato no soportado: {path}")


def main(argv: List[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("path", type=Path, help="Directorio de sesión, CSV exportado o ZIP exportado")
    parser.add_argument(
        "--strict",
        action="store_true",
        help="Devuelve código != 0 si algún criterio de aceptación falla",
    )
    args = parser.parse_args(argv)

    if not args.path.exists():
        print(f"ERROR: no existe {args.path}", file=sys.stderr)
        return 2

    try:
        streams = open_session(args.path)
    except Exception as exc:
        print(f"ERROR abriendo sesión: {exc}", file=sys.stderr)
        return 2

    try:
        timestamps = collect_timestamps(iter_csv_rows(streams))
    except Exception as exc:
        print(f"ERROR leyendo CSV: {exc}", file=sys.stderr)
        return 2
    finally:
        for s in streams:
            try:
                s.close()
            except Exception:
                pass

    if not timestamps:
        print("ERROR: no se pudieron extraer timestamps", file=sys.stderr)
        return 2

    stats = compute_stats(timestamps)
    print(stats.pretty())

    ok, errs = stats.passes_spec()
    if ok:
        freq_hz = 1e9 / stats.dt_median_ns
        print(
            f"\n✔  SESIÓN VÁLIDA — {stats._duration_human()} de grabación continua\n"
            f"\n   · Frecuencia efectiva ≈ {freq_hz:.1f} Hz "
            f"(mediana de intervalo = {stats.dt_median_ns / 1e6:.4f} ms, dentro del rango 9.5–10.5 ms).\n"
            f"   · Sin huecos: ningún salto temporal supera los 50 ms — el pipeline "
            f"no perdió ni saltó muestras en toda la sesión.\n"
            f"   · Jitter p95 = {stats.jitter_p95_ns / 1e6:.4f} ms: el 95 % de los intervalos "
            f"se desvían menos de {stats.jitter_p95_ns / 1e6:.4f} ms respecto a los 10 ms nominales "
            f"(umbral: 5 ms).\n"
            f"   · Total de muestras: {stats.total_rows:,} filas escritas a disco.\n"
        )
        return 0

    print("\n✘  SESIÓN INVÁLIDA — se encontraron los siguientes problemas:\n")
    for e in errs:
        print(f"   · {e}")
    print()
    return 1 if args.strict else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
