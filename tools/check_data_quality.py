#!/usr/bin/env python3
"""Comprueba la calidad de los datos de una sesión de grabación IMU.

Para cada columna del CSV reporta:
  * % de valores vacíos (sensor no disponible o no entregó datos)
  * min, max, media, std
  * p1 / p50 / p99 para detectar colas/outliers
  * cuántos valores son exactamente 0.0 (posible sensor "muerto")
  * primera y última muestra donde el sensor entregó dato

Además agrupa las columnas por sensor y avisa si algún grupo está completamente
vacío o parcialmente roto.

Uso:
    python3 tools/check_data_quality.py /ruta/a/sesion_dir_o_csv_o_zip
    python3 tools/check_data_quality.py sesion.zip --verbose     # imprime cada columna
    python3 tools/check_data_quality.py sesion.zip --sample 50000 # muestrea para ir más rápido

Dependencias: sólo biblioteca estándar de Python 3.8+.
"""
from __future__ import annotations

import argparse
import csv
import io
import math
import sys
import tempfile
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Iterable, Iterator, List, Optional, Tuple


SENSOR_GROUPS: Dict[str, List[str]] = {
    # "Accelerometer (raw)":   ["acc_x", "acc_y", "acc_z"],         # desactivado del CSV
    "Linear acceleration":   ["lin_x", "lin_y", "lin_z"],
    "Gravity":               ["grav_x", "grav_y", "grav_z"],
    "Gyroscope":             ["gyro_x", "gyro_y", "gyro_z"],
    # "Rotation (orientation)":["rot_yaw", "rot_pitch", "rot_roll"],  # desactivado
    # "Magnetometer (heading)":["mag_heading"],                        # desactivado
    # "Derived magnitudes":    ["acc_magnitude", "gyro_magnitude"],    # desactivado
}

TIMESTAMP_COLS = {"timestamp_ns"}


@dataclass
class ColumnStats:
    name: str
    total: int = 0
    empty: int = 0
    zeros: int = 0
    n: int = 0
    sum_v: float = 0.0
    sum_sq: float = 0.0
    min_v: float = math.inf
    max_v: float = -math.inf
    samples: List[float] = field(default_factory=list)   # para percentiles
    first_seen_row: Optional[int] = None
    last_seen_row: Optional[int] = None

    def update(self, row_idx: int, raw: str) -> None:
        self.total += 1
        if raw == "" or raw is None:
            self.empty += 1
            return
        try:
            v = float(raw)
        except ValueError:
            self.empty += 1
            return
        if math.isnan(v):
            self.empty += 1
            return
        self.n += 1
        self.sum_v += v
        self.sum_sq += v * v
        if v < self.min_v:
            self.min_v = v
        if v > self.max_v:
            self.max_v = v
        if v == 0.0:
            self.zeros += 1
        if self.first_seen_row is None:
            self.first_seen_row = row_idx
        self.last_seen_row = row_idx
        self.samples.append(v)

    @property
    def empty_ratio(self) -> float:
        return self.empty / self.total if self.total else 0.0

    @property
    def zero_ratio(self) -> float:
        return self.zeros / self.n if self.n else 0.0

    @property
    def mean(self) -> float:
        return self.sum_v / self.n if self.n else float("nan")

    @property
    def std(self) -> float:
        if self.n < 2:
            return float("nan")
        var = max(0.0, self.sum_sq / self.n - self.mean ** 2)
        return math.sqrt(var)

    def percentile(self, p: float) -> float:
        if not self.samples:
            return float("nan")
        s = sorted(self.samples)
        idx = int((len(s) - 1) * p)
        return s[idx]


# ---------- I/O helpers (idénticos a validate_session.py) ------------------

def open_streams(path: Path) -> List[io.TextIOBase]:
    if path.is_dir():
        chunks = sorted(path.glob("chunk_*.csv"))
        if not chunks:
            raise FileNotFoundError(f"No hay chunk_*.csv en {path}")
        return [c.open("r", encoding="utf-8") for c in chunks]
    if path.suffix.lower() == ".zip":
        tmpdir = tempfile.mkdtemp(prefix="imuflux_qc_")
        with zipfile.ZipFile(path) as zf:
            zf.extractall(tmpdir)
        # Busca un subdirectorio con chunks o chunks en la raíz
        session_dirs = [
            p for p in Path(tmpdir).iterdir()
            if p.is_dir() and any(p.glob("chunk_*.csv"))
        ]
        if session_dirs:
            return open_streams(session_dirs[0])
        chunks = sorted(Path(tmpdir).glob("chunk_*.csv"))
        if chunks:
            return [c.open("r", encoding="utf-8") for c in chunks]
        raise FileNotFoundError(f"ZIP sin chunks reconocibles: {path}")
    if path.suffix.lower() == ".csv":
        return [path.open("r", encoding="utf-8")]
    raise ValueError(f"Formato no soportado: {path}")


def iter_rows(streams: Iterable[io.TextIOBase]) -> Iterator[Tuple[List[str], List[str]]]:
    """Yields (header, row). Salta cabeceras duplicadas en chunks siguientes."""
    first_header: Optional[List[str]] = None
    for stream in streams:
        reader = csv.reader(stream)
        try:
            header = next(reader)
        except StopIteration:
            continue
        if first_header is None:
            first_header = header
        for row in reader:
            if row:
                yield first_header, row


# ---------- Análisis principal --------------------------------------------

def analyse(path: Path, sample_limit: Optional[int] = None) -> Tuple[List[str], Dict[str, ColumnStats]]:
    streams = open_streams(path)
    try:
        header: Optional[List[str]] = None
        stats: Dict[str, ColumnStats] = {}
        for row_idx, (hdr, row) in enumerate(iter_rows(streams)):
            if header is None:
                header = hdr
                stats = {name: ColumnStats(name=name) for name in header}
            # Alinear longitud (filas con menos columnas → resto '')
            if len(row) < len(header):
                row = row + [""] * (len(header) - len(row))
            for col_idx, cell in enumerate(row):
                if col_idx >= len(header):
                    break
                col_name = header[col_idx]
                cs = stats[col_name]
                # Para evitar memoria O(N) en sesiones largas, descartamos muestras
                # a partir del sample_limit — seguimos contando total/empty/zeros.
                if sample_limit and cs.n >= sample_limit:
                    prev_samples = cs.samples
                    cs.samples = []  # liberamos
                    cs_update_light(cs, row_idx, cell)
                    cs.samples = prev_samples  # restauramos referencia vacía
                else:
                    cs.update(row_idx, cell)
        return header or [], stats
    finally:
        for s in streams:
            try:
                s.close()
            except Exception:
                pass


def cs_update_light(cs: ColumnStats, row_idx: int, raw: str) -> None:
    """Como update pero sin guardar muestra (ahorra RAM en sesiones largas)."""
    cs.total += 1
    if raw == "":
        cs.empty += 1
        return
    try:
        v = float(raw)
    except ValueError:
        cs.empty += 1
        return
    if math.isnan(v):
        cs.empty += 1
        return
    cs.n += 1
    cs.sum_v += v
    cs.sum_sq += v * v
    if v < cs.min_v:
        cs.min_v = v
    if v > cs.max_v:
        cs.max_v = v
    if v == 0.0:
        cs.zeros += 1
    cs.last_seen_row = row_idx


# ---------- Impresión --------------------------------------------------------

def fmt_pct(x: float) -> str:
    return f"{x * 100:6.2f}%"


def fmt_num(x: float) -> str:
    if math.isnan(x) or math.isinf(x):
        return "   n/a  "
    if abs(x) >= 1e6 or (0 < abs(x) < 1e-3):
        return f"{x:+10.3e}"
    return f"{x:+10.4f}"


def print_column(cs: ColumnStats) -> None:
    print(f"  {cs.name:<16}  "
          f"empty={fmt_pct(cs.empty_ratio)}  "
          f"zeros={fmt_pct(cs.zero_ratio)}  "
          f"min={fmt_num(cs.min_v)}  "
          f"max={fmt_num(cs.max_v)}  "
          f"mean={fmt_num(cs.mean)}  "
          f"std={fmt_num(cs.std)}")


def classify_group(cols: List[ColumnStats]) -> str:
    if not cols:
        return "??"
    empty_ratios = [c.empty_ratio for c in cols]
    max_empty = max(empty_ratios)
    min_empty = min(empty_ratios)
    all_zero = all(c.n == 0 or (c.min_v == 0.0 and c.max_v == 0.0) for c in cols)
    if max_empty >= 0.999:
        return "✘ SIN DATOS — el sensor no entregó ningún valor"
    if max_empty >= 0.05:
        return f"⚠ datos parciales — hasta {fmt_pct(max_empty).strip()} vacío"
    if all_zero:
        return "⚠ todos los valores son 0.0 — posible sensor muerto o bloqueado"
    if max_empty - min_empty > 0.01:
        return "⚠ cobertura desigual entre ejes"
    return "✔ completo"


def main(argv: List[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("path", type=Path, help="Directorio de sesión, CSV o ZIP")
    parser.add_argument("--verbose", "-v", action="store_true",
                        help="Imprime estadísticas detalladas por columna")
    parser.add_argument("--sample", type=int, default=200_000,
                        help="Tope de muestras por columna para percentiles (default: 200k)")
    args = parser.parse_args(argv)

    if not args.path.exists():
        print(f"ERROR: no existe {args.path}", file=sys.stderr)
        return 2

    try:
        header, stats = analyse(args.path, sample_limit=args.sample)
    except Exception as exc:
        print(f"ERROR leyendo sesión: {exc}", file=sys.stderr)
        return 2

    if not stats:
        print("ERROR: sesión vacía", file=sys.stderr)
        return 2

    total_rows = max(cs.total for cs in stats.values())
    print(f"\nSesión: {args.path}")
    print(f"Columnas detectadas: {len(header)}")
    print(f"Filas totales: {total_rows:,}\n")

    # Resumen por grupo de sensor
    print("Resumen por sensor")
    print("-" * 72)
    problems = 0
    for group_name, cols in SENSOR_GROUPS.items():
        group_cols = [stats[c] for c in cols if c in stats]
        if not group_cols:
            continue
        verdict = classify_group(group_cols)
        if verdict.startswith(("✘", "⚠")):
            problems += 1
        print(f"  {group_name:<24} {verdict}")
    print()

    # Detalle por columna
    if args.verbose:
        print("Detalle por columna")
        print("-" * 72)
        for name in header:
            cs = stats[name]
            if name in TIMESTAMP_COLS:
                # Sólo reportamos cobertura en timestamps
                print(f"  {name:<16}  empty={fmt_pct(cs.empty_ratio)}  "
                      f"rango=[{int(cs.min_v)}, {int(cs.max_v)}]")
            else:
                print_column(cs)
                if cs.samples:
                    print(f"  {'':<16}  p1={fmt_num(cs.percentile(0.01))}  "
                          f"p50={fmt_num(cs.percentile(0.50))}  "
                          f"p99={fmt_num(cs.percentile(0.99))}")
        print()

    # Columnas sospechosas en modo no-verbose
    if not args.verbose:
        suspicious = [cs for cs in stats.values()
                      if cs.name not in TIMESTAMP_COLS
                      and (cs.empty_ratio > 0.001 or cs.n == 0
                           or (cs.n > 0 and cs.min_v == 0.0 and cs.max_v == 0.0))]
        if suspicious:
            print("Columnas con posibles problemas")
            print("-" * 72)
            for cs in suspicious:
                print_column(cs)
            print()

    if problems:
        print(f"RESULTADO: {problems} grupo(s) de sensores con problemas detectados.")
        return 1
    print("RESULTADO: todos los sensores devolvieron datos coherentes.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
