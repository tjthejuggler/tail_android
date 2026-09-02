#!/usr/bin/env python3
"""Generate the map-detail assets for the MapScreen world map.

Reads Natural Earth GeoJSON files downloaded to /tmp/ne and writes compact
JSON assets into app/src/main/assets:

  - world_borders_110m.json : country border polylines (always shown, faint)
  - world_borders_50m.json  : higher-detail border polylines (shown zoomed in)
  - world_land_50m.json     : higher-detail land polygons (shown zoomed in)
  - world_cities.json       : populated places as [lon, lat, popRank, name]
                              popRank: 0 = mega city ... 4 = small town

Coordinate precision is truncated to 2 decimals (~1.1 km) to keep file
sizes small on Android.

Usage:  python3 scripts/gen_map_detail_assets.py
"""
import json
import os

SRC = "/tmp/ne"
DEST = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets")


def load(name):
    with open(os.path.join(SRC, name)) as f:
        return json.load(f)


def round_pt(pt):
    return [round(pt[0], 2), round(pt[1], 2)]


def polylines_from(geojson):
    """Flatten all LineString/MultiLineString geometries into polylines."""
    out = []
    for feat in geojson["features"]:
        geom = feat["geometry"]
        if geom is None:
            continue
        t = geom["type"]
        if t == "LineString":
            out.append([round_pt(p) for p in geom["coordinates"]])
        elif t == "MultiLineString":
            for line in geom["coordinates"]:
                out.append([round_pt(p) for p in line])
    return out


def polygons_from(geojson):
    """Flatten all Polygon/MultiPolygon outer rings into polygon rings."""
    out = []
    for feat in geojson["features"]:
        geom = feat["geometry"]
        if geom is None:
            continue
        t = geom["type"]
        polys = [geom["coordinates"]] if t == "Polygon" else geom["coordinates"]
        for poly in polys:
            ring = poly[0]  # outer ring only (holes invisible at this scale)
            if len(ring) >= 3:
                out.append([round_pt(p) for p in ring])
    return out


def area_rank(km2):
    if km2 >= 3_000_000:
        return 0
    if km2 >= 1_000_000:
        return 1
    if km2 >= 300_000:
        return 2
    if km2 >= 100_000:
        return 3
    return 4


def ring_area_km2(ring):
    """Approximate spherical polygon area (shoelace in degrees, weighted by
    cos(mean latitude)). Plenty accurate for size-tiering only."""
    import math
    s = 0.0
    for i in range(len(ring)):
        lon1, lat1 = ring[i]
        lon2, lat2 = ring[(i + 1) % len(ring)]
        s += (math.radians(lon2) - math.radians(lon1)) * (
            2 + math.sin(math.radians(lat1)) + math.sin(math.radians(lat2))
        )
    return abs(s) * 6_371_000**2 / 2


def country_labels_from(geojson):
    """Country label points: [lon, lat, areaRank, name].

    The centroid is the bbox centre of the LARGEST polygon of each country
    (good enough for label placement at world-map scale).
    """
    out = []
    for feat in geojson["features"]:
        props = feat["properties"]
        name = props.get("NAME") or props.get("name")
        geom = feat["geometry"]
        if not name or geom is None:
            continue
        t = geom["type"]
        polys = [geom["coordinates"]] if t == "Polygon" else geom["coordinates"]
        best = None  # (area, centroid, wDeg, hDeg)
        for poly in polys:
            ring = poly[0]
            area = ring_area_km2(ring)
            if best is None or area > best[0]:
                lons = [p[0] for p in ring]
                lats = [p[1] for p in ring]
                w = max(lons) - min(lons)
                h = max(lats) - min(lats)
                best = (area, ((min(lons) + max(lons)) / 2, (min(lats) + max(lats)) / 2), w, h)
        if best is None:
            continue
        out.append([
            round(best[1][0], 2), round(best[1][1], 2),
            area_rank(best[0]),
            round(best[2], 2), round(best[3], 2),
            name,
        ])
    out.sort(key=lambda c: c[2])
    return out


def pop_rank(pop_max):
    if pop_max >= 5_000_000:
        return 0
    if pop_max >= 1_000_000:
        return 1
    if pop_max >= 250_000:
        return 2
    if pop_max >= 50_000:
        return 3
    return 4


def cities_from(geojson):
    out = []
    for feat in geojson["features"]:
        props = feat["properties"]
        name = props.get("name") or props.get("nameascii")
        if not name:
            continue
        lon, lat = feat["geometry"]["coordinates"][:2]
        pop = props.get("pop_max") or 0
        # Skip tiny places entirely — they'd never render visibly.
        if pop < 50_000:
            continue
        out.append([round(lon, 2), round(lat, 2), pop_rank(pop), name])
    # Big cities first so overlap-culling keeps the important ones.
    out.sort(key=lambda c: c[2])
    return out


def write(name, data):
    path = os.path.join(DEST, name)
    with open(path, "w") as f:
        json.dump(data, f, separators=(",", ":"))
    print(f"{name}: {os.path.getsize(path) // 1024} KiB")


if __name__ == "__main__":
    write("world_borders_110m.json", polylines_from(load("borders110.geojson")))
    write("world_borders_50m.json", polylines_from(load("borders50.geojson")))
    write("world_land_50m.json", polygons_from(load("land50.geojson")))
    write("world_cities.json", cities_from(load("cities.geojson")))
    write("world_country_labels.json", country_labels_from(load("countries110.geojson")))
