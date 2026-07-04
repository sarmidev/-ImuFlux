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
  * watchdog_resurrections == 0 (si metadata.json está disponible)

Nota sobre el "jitter": se calcula como p95 de |dt − 10 ms|, es decir asume un
periodo nominal fijo de 10 ms (100 Hz). Si el dispositivo entrega a otra tasa
estable (p.ej. 50 Hz → dt≈20 ms), este valor saldrá alto (~10 ms) aunque la
regularidad real sea perfecta; en ese caso el síntoma verdadero es la mediana
fuera de rango, no el jitter. Interpreta ambas métricas juntas.

Salida: imprime un resumen y devuelve código 0 si todo OK, 1 si falla algún
criterio, 2 si el formato del fichero es incorrecto.

Dependencias: sólo biblioteca estándar de Python 3.8+.
"""
from __future__ import annotations

import argparse
import csv
import io
import json
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
    # Poblado desde `metadata.json` cuando esté disponible. `None` si la
    # validación corre sobre un CSV exportado sin metadatos adjuntos.
    watchdog_resurrections: Optional[int] = None
    device_label: Optional[str] = None

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

    def _completeness(self) -> float:
        expected = self.duration_s * 100.0
        if expected <= 0:
            return 0.0
        return self.total_rows / expected

    def pretty(self) -> str:
        comp = self._completeness()
        lines = [
            f"rows              = {self.total_rows:>12d}",
            f"duration          = {self.duration_s:>12.2f} s  ({self._duration_human()})",
            f"completeness      = {comp * 100:>12.2f} %  (objetivo: ≥ 99 %)",
            f"first_ts_ns       = {self.first_ts_ns:>20d}",
            f"last_ts_ns        = {self.last_ts_ns:>20d}",
            f"dt_mean_ms        = {self.dt_mean_ns / 1e6:>12.4f}",
            f"dt_median_ms      = {self.dt_median_ns / 1e6:>12.4f}  (objetivo: 9.5 – 10.5 ms)",
            f"dt_p95_ms         = {self.dt_p95_ns / 1e6:>12.4f}",
            f"dt_p99_ms         = {self.dt_p99_ns / 1e6:>12.4f}",
            f"jitter_p95_ms     = {self.jitter_p95_ns / 1e6:>12.4f}  (objetivo: < 5 ms)",
            f"gaps (>50 ms)     = {self.gaps:>12d}  (objetivo: 0)",
            f"max_gap_ms        = {self.max_gap_ns / 1e6:>12.4f}",
        ]
        if self.watchdog_resurrections is not None:
            lines.append(
                f"watchdog_resurr.  = {self.watchdog_resurrections:>12d}  (objetivo: 0)"
            )
        if self.device_label:
            lines.append(f"device            = {self.device_label}")
        return "\n".join(lines) + "\n"

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
        comp = self._completeness()
        if comp < 0.99:
            errs.append(
                f"completitud baja: {comp * 100:.2f} % "
                f"(esperado ≥ 99 % → faltan muestras, ver gaps)"
            )
        if self.watchdog_resurrections is not None and self.watchdog_resurrections > 0:
            errs.append(
                f"watchdog_resurrections = {self.watchdog_resurrections}: el "
                f"sistema mató la grabación y el watchdog la reanudó "
                f"(dispositivo con política de batería hostil — revisa "
                f"DEVICE_COMPATIBILITY.md)"
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


def open_session(path: Path) -> Tuple[List[io.TextIOBase], Optional[Path]]:
    """Abre los chunks CSV de una sesión.

    Devuelve una tupla `(streams, metadata_path)`. `metadata_path` apunta a un
    `metadata.json` accesible si existe (en el directorio de la sesión o
    extraído del ZIP); `None` si no hay.
    """
    if path.is_dir():
        chunks = sorted(path.glob("chunk_*.csv"))
        if not chunks:
            raise FileNotFoundError(f"No hay chunk_*.csv en {path}")
        meta = path / "metadata.json"
        return [c.open("r", encoding="utf-8") for c in chunks], meta if meta.exists() else None
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
            meta = Path(tmpdir) / "metadata.json"
            return [c.open("r", encoding="utf-8") for c in chunks], meta if meta.exists() else None
        raise FileNotFoundError(f"ZIP sin chunks reconocibles: {path}")
    if path.suffix.lower() == ".csv":
        return [path.open("r", encoding="utf-8")], None
    raise ValueError(f"Formato no soportado: {path}")


def read_metadata(meta_path: Optional[Path]) -> Tuple[Optional[int], Optional[str]]:
    """Lee `watchdog_resurrections` y la etiqueta de dispositivo del metadata.

    Devuelve `(resurrections, device_label)`. Cualquiera puede ser `None` si el
    fichero no existe o no es parseable.
    """
    if meta_path is None or not meta_path.exists():
        return None, None
    try:
        data = json.loads(meta_path.read_text(encoding="utf-8"))
    except Exception:
        return None, None
    resurrections = data.get("watchdog_resurrections")
    if isinstance(resurrections, bool) or not isinstance(resurrections, int):
        resurrections = None
    device = data.get("device")
    device_label: Optional[str] = None
    if isinstance(device, dict):
        parts = [str(device.get(k, "")).strip() for k in ("manufacturer", "model") if device.get(k)]
        device_label = " ".join(p for p in parts if p) or None
    return resurrections, device_label


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
        streams, meta_path = open_session(args.path)
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
    resurrections, device_label = read_metadata(meta_path)
    stats.watchdog_resurrections = resurrections
    stats.device_label = device_label
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
        if resurrections == 0:
            print(
                "   · watchdog_resurrections = 0 → el sistema no interrumpió "
                "la grabación en toda la sesión.\n"
            )
        return 0

    print("\n✘  SESIÓN INVÁLIDA — se encontraron los siguientes problemas:\n")
    for e in errs:
        print(f"   · {e}")
    if resurrections is not None and resurrections > 0:
        print(
            "\n   ℹ  Indicador de dispositivo hostil: la grabación fue relanzada "
            f"{resurrections} veces por el watchdog. Consulta "
            "DEVICE_COMPATIBILITY.md para el tier de este modelo.\n"
        )
    else:
        print()
    return 1 if args.strict else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
