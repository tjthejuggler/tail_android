#!/usr/bin/env python3
"""Generate the map-detail assets for the MapScreen world map.

Reads Natural Earth GeoJSON files downloaded to .tmp/ne and writes compact
JSON assets into app/src/main/assets:

  - world_borders_110m.json : country border polylines (always shown, faint)
  - world_borders_50m.json  : higher-detail border polylines (shown zoomed in)
  - world_land_50m.json     : higher-detail land polygons (shown zoomed in)
  - world_cities.json       : populated places as [lon, lat, popRank, name]
                              popRank: 0 = mega city ... 4 = small town
  - world_country_labels.json : country label points as
                              [lon, lat, areaRank, wDeg, hDeg, name]; one
                              entry PER major landmass so multi-part countries
                              (Malaysia, Indonesia, Denmark ...) light up the
                              country facts panel when zoomed into any part.

Coordinate precision is truncated to 2 decimals (~1.1 km) to keep file
sizes small on Android.

Usage:  python3 scripts/gen_map_detail_assets.py [asset-name ...]

With no arguments every asset is regenerated; pass names (e.g.
world_country_labels.json) to regenerate only those. Each Natural Earth
source can be fetched with the URLs noted in SRC_URLS below.
"""
import json
import os
import sys

SRC = os.path.join(os.path.dirname(__file__), "..", ".tmp", "ne")
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


MIN_RING_AREA_KM2 = 1_000  # drop islets; keeps Luxembourg (2.4k km²) detectable


def area_rank(km2):
    """0 (huge) … 4 (small) — gates how early a label appears while zooming."""
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
    """Approximate spherical polygon area in km2 (shoelace in degrees,
    weighted by sin of latitude). Plenty accurate for size-tiering only."""
    import math
    s = 0.0
    for i in range(len(ring)):
        lon1, lat1 = ring[i]
        lon2, lat2 = ring[(i + 1) % len(ring)]
        s += (math.radians(lon2) - math.radians(lon1)) * (
            2 + math.sin(math.radians(lat1)) + math.sin(math.radians(lat2))
        )
    return abs(s) * (6_371.0**2) / 2


def country_labels_from(geojson):
    """Country label points: [lon, lat, areaRank, wDeg, hDeg, name].

    Emits one entry PER major landmass, not just the country's largest
    polygon: multi-part countries (Malaysia's Borneo vs peninsula, Denmark's
    Jutland vs Zealand, Indonesia ...) previously got a single bbox centred
    on the biggest island, so zooming into their other parts never matched
    any bbox and the country facts panel stayed hidden.
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
        parts = []
        for poly in polys:
            ring = poly[0]
            area = ring_area_km2(ring)
            if area < MIN_RING_AREA_KM2:
                continue
            lons = [p[0] for p in ring]
            lats = [p[1] for p in ring]
            w = max(lons) - min(lons)
            h = max(lats) - min(lats)
            parts.append((area, ((min(lons) + max(lons)) / 2, (min(lats) + max(lats)) / 2), w, h))
        if not parts:
            continue
        # Size class comes from the largest part, so a peninsula of a big
        # country still counts as "big" for facts detection and label timing.
        rank = area_rank(max(p[0] for p in parts))
        for area, centroid, w, h in parts:
            out.append([
                round(centroid[0], 2), round(centroid[1], 2),
                rank,
                round(w, 2), round(h, 2),
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


SOURCES = {
    "world_borders_110m.json": ("borders110.geojson", polylines_from),
    "world_borders_50m.json": ("borders50.geojson", polylines_from),
    "world_land_50m.json": ("land50.geojson", polygons_from),
    "world_cities.json": ("cities.geojson", cities_from),
    "world_country_labels.json": ("countries110.geojson", country_labels_from),
}

SRC_URLS = {
    "borders110.geojson": "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_110m_admin_0_countries.geojson",
    "borders50.geojson": "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_50m_admin_0_countries.geojson",
    "land50.geojson": "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_50m_land.geojson",
    "cities.geojson": "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_10m_populated_places_simple.geojson",
    "countries110.geojson": "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_110m_admin_0_countries.geojson",
}

if __name__ == "__main__":
    wanted = sys.argv[1:] or list(SOURCES)
    for name in wanted:
        src_file, convert = SOURCES[name]
        src_path = os.path.join(SRC, src_file)
        if not os.path.exists(src_path):
            sys.exit(f"missing source {src_path} — fetch it from:\n  {SRC_URLS[src_file]}")
        write(name, convert(load(src_file)))
