#!/usr/bin/env python3
"""Validate the published North Whangarei shapefile and build the MapSafe sample.

The source archive contains point geometry only.  This script preserves every
coordinate and adds explicitly synthetic attributes used to exercise the
NextGIS Mobile collection, masking, encryption, and community-sharing paths.
It intentionally depends only on the Python standard library.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import struct
import zipfile
from pathlib import Path


EXPECTED_SOURCE_SHA256 = "958838d456e3d97448c4abc129e0b34844f96339459aab1a0cca99b2abb1408a"
EXPECTED_POINT_COUNT = 23
EXPECTED_BOUNDS = (
    173.79867234289532,
    -35.68653361912114,
    174.11298826237947,
    -35.48709981118292,
)


def parse_args() -> argparse.Namespace:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--source",
        type=Path,
        default=root
        / "paper"
        / "mapsafe-results"
        / "datasets"
        / "north-whangarei"
        / "source"
        / "north_whangarei-SHP.zip",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=root
        / "app"
        / "src"
        / "main"
        / "assets"
        / "mapsafe"
        / "north_whangarei_infected_trees.geojson",
    )
    parser.add_argument(
        "--manifest",
        type=Path,
        default=root
        / "paper"
        / "mapsafe-results"
        / "datasets"
        / "north-whangarei"
        / "dataset-manifest.json",
    )
    return parser.parse_args()


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def parse_point_shapefile(data: bytes) -> tuple[list[tuple[float, float]], tuple[float, ...]]:
    if len(data) < 100 or struct.unpack_from(">i", data, 0)[0] != 9994:
        raise ValueError("POINT.shp is not an ESRI shapefile")
    if struct.unpack_from("<i", data, 32)[0] != 1:
        raise ValueError("The case-study shapefile must contain POINT geometry")
    declared_bytes = struct.unpack_from(">i", data, 24)[0] * 2
    if declared_bytes != len(data):
        raise ValueError("The shapefile header length does not match the archive content")

    bounds = struct.unpack_from("<4d", data, 36)
    points: list[tuple[float, float]] = []
    offset = 100
    expected_record: int | None = None
    while offset < len(data):
        if offset + 8 > len(data):
            raise ValueError("The final shapefile record header is incomplete")
        record_number, content_words = struct.unpack_from(">ii", data, offset)
        content_bytes = content_words * 2
        if expected_record is None:
            expected_record = record_number
        if record_number != expected_record or content_bytes != 20:
            raise ValueError("The shapefile contains an unexpected or malformed point record")
        if offset + 8 + content_bytes > len(data):
            raise ValueError("A shapefile point record extends beyond the file")
        if struct.unpack_from("<i", data, offset + 8)[0] != 1:
            raise ValueError("The shapefile contains non-point geometry")
        longitude, latitude = struct.unpack_from("<2d", data, offset + 12)
        if not (-180 <= longitude <= 180 and -90 <= latitude <= 90):
            raise ValueError("The shapefile contains an invalid WGS84 coordinate")
        points.append((longitude, latitude))
        expected_record += 1
        offset += 8 + content_bytes
    return points, bounds


def parse_dbf_header(data: bytes) -> tuple[int, int]:
    if len(data) < 33:
        raise ValueError("POINT.dbf is incomplete")
    record_count, header_bytes, record_bytes = struct.unpack_from("<IHH", data, 4)
    if header_bytes < 33 or record_bytes < 1:
        raise ValueError("POINT.dbf has an invalid header")
    field_count = (header_bytes - 33) // 32
    return record_count, field_count


def synthetic_properties(index: int) -> dict[str, object]:
    return {
        "tree_id": index,
        "site_code": f"NW-{index:03d}",
        "observation": "Fictional infected-tree location",
        "record_status": "Synthetic case-study record",
        "sensitivity": "Precise location restricted",
        "data_guardian": "Steven (field data custodian)",
        "community": "North Whangarei biodiversity community",
        "source_study": "Sharma et al. (2024), doi:10.1111/tgis.13153",
    }


def main() -> None:
    args = parse_args()
    source_bytes = args.source.read_bytes()
    source_digest = sha256(source_bytes)
    if source_digest != EXPECTED_SOURCE_SHA256:
        raise ValueError(
            "The source archive differs from the reviewed GitHub artifact: "
            f"expected {EXPECTED_SOURCE_SHA256}, found {source_digest}"
        )

    with zipfile.ZipFile(args.source) as archive:
        names = {Path(name).name.lower(): name for name in archive.namelist() if not name.endswith("/")}
        required = {"point.shp", "point.shx", "point.dbf", "point.prj"}
        missing = required - names.keys()
        if missing:
            raise ValueError(f"The source archive is missing: {', '.join(sorted(missing))}")
        shp = archive.read(names["point.shp"])
        dbf = archive.read(names["point.dbf"])
        prj = archive.read(names["point.prj"]).decode("ascii", errors="strict")

    if "GCS_WGS_1984" not in prj or "UNIT[\"Degree\"" not in prj:
        raise ValueError("The source projection is not the reviewed WGS84 geographic CRS")
    points, bounds = parse_point_shapefile(shp)
    dbf_records, source_field_count = parse_dbf_header(dbf)
    if len(points) != EXPECTED_POINT_COUNT or dbf_records != EXPECTED_POINT_COUNT:
        raise ValueError("The source record count differs from the reviewed 23-point dataset")
    if any(abs(actual - expected) > 1e-12 for actual, expected in zip(bounds, EXPECTED_BOUNDS)):
        raise ValueError("The source extent differs from the reviewed dataset")

    feature_collection = {
        "type": "FeatureCollection",
        "name": "north-whangarei-infected-trees",
        "crs": {"type": "name", "properties": {"name": "urn:ogc:def:crs:OGC:1.3:CRS84"}},
        "mapsafe_provenance": {
            "source_archive_sha256": source_digest,
            "coordinate_policy": "Coordinates preserved exactly from POINT.shp",
            "attribute_policy": "Demonstration attributes are synthetic and were not present in POINT.dbf",
        },
        "features": [
            {
                "type": "Feature",
                "id": index,
                "geometry": {"type": "Point", "coordinates": [longitude, latitude]},
                "properties": synthetic_properties(index),
            }
            for index, (longitude, latitude) in enumerate(points, start=1)
        ],
    }
    output_bytes = (json.dumps(feature_collection, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(output_bytes)

    manifest = {
        "schema_version": 1,
        "source": {
            "repository_url": "https://github.com/sharmapn/geonode_datasovereignty",
            "archive_path": "datasets/north_whangarei-SHP.zip",
            "sha256": source_digest,
            "crs": "WGS84 geographic (GCS_WGS_1984)",
            "geometry_type": "Point",
            "point_count": len(points),
            "attribute_field_count": source_field_count,
            "bounds": list(bounds),
        },
        "mobile_sample": {
            "path": "app/src/main/assets/mapsafe/north_whangarei_infected_trees.geojson",
            "sha256": sha256(output_bytes),
            "point_count": len(points),
            "attribute_field_count": len(synthetic_properties(1)),
            "attributes_are_synthetic": True,
        },
    }
    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    args.manifest.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
