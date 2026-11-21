#!/usr/bin/env python3
# c:\Users\Acer\Downloads\gsm\roadpulse_lite\tools\compile_poi_pack.py
# Python 3.12 / Build-time offline spatial database compiler for RoadPulse Lite (.rpoi v1)
import json
import math
import os
import re
import struct
import time
import urllib.parse
import urllib.request
import zlib

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
GSM_DIR = os.path.abspath(os.path.join(ROOT, ".."))
CACHE_DIR = os.path.join(ROOT, "tools", "osm_cache")
ASSETS_DIR = os.path.join(ROOT, "app", "assets")
os.makedirs(CACHE_DIR, exist_ok=True)
os.makedirs(ASSETS_DIR, exist_ok=True)

# Category constants matching SpatialRadarEngine.java
CAT_CAM_PENALTY = 1      # Camera phạt nguội / AI 24/7
CAT_CAM_RED_LIGHT = 2    # Camera vượt đèn đỏ
CAT_CAM_SPEED = 3        # Camera bắn tốc độ
CAT_TRAFFIC_LIGHT = 4    # Đèn đỏ / ngã tư tín hiệu
CAT_POLICE = 5           # Chốt CSGT / điểm kiểm tra vi phạm
CAT_URBAN_SIGN = 6       # Biển khu dân cư / giới hạn tốc độ
CAT_WARNING_SIGN = 7     # Biển nguy hiểm / đường sắt / đèo dốc / cấm xe máy
CAT_ROAD_HAZARD = 8      # Đường xấu / thi công / thu phí / lối ra / đo khoảng cách

# Flags bitmask
FLAG_MOTO = 1 << 0
FLAG_CAR = 1 << 1
FLAG_REDLIGHT = 1 << 2
FLAG_MOTO_PROHIBITED = 1 << 3
FLAG_TWO_WAY = 1 << 4

HEADING_OMNI = 65535

OVERPASS_URLS = [
    "https://overpass-api.de/api/interpreter",
    "https://lz4.overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
]

# Hanoi Inner City + Corridor to Lewe2k6 (22.0554663, 106.6136236 - Na Sam, Van Lang, Lang Son)
CURATED_POIS = [
    # --- NỘI THÀNH HÀ NỘI: CÁC ĐIỂM CAMERA AI / PHẠT NGUỘI & ĐÈN ĐỎ LỚN ---
    (21.0369, 105.8347, HEADING_OMNI, CAT_CAM_PENALTY, 50, FLAG_MOTO | FLAG_CAR | FLAG_REDLIGHT, "Cam AI Điện Biên Phủ - Trần Phú"),
    (21.0315, 105.8406, HEADING_OMNI, CAT_CAM_RED_LIGHT, 50, FLAG_MOTO | FLAG_CAR | FLAG_REDLIGHT, "Cam AI đèn đỏ Cửa Nam - Hai Bà Trưng"),
    (21.0244, 105.8535, HEADING_OMNI, CAT_CAM_RED_LIGHT, 50, FLAG_MOTO | FLAG_CAR | FLAG_REDLIGHT, "Cam AI đèn đỏ Phố Huế - Trần Hưng Đạo"),
    (21.0221, 105.8529, HEADING_OMNI, CAT_CAM_RED_LIGHT, 50, FLAG_MOTO | FLAG_CAR | FLAG_REDLIGHT, "Cam AI Phố Huế - Nguyễn Du / Tuệ Tĩnh"),
    (21.0121, 105.8500, HEADING_OMNI, CAT_CAM_PENALTY, 50, FLAG_MOTO | FLAG_CAR | FLAG_REDLIGHT, "Cam AI ngã tư Đại Cồ Việt - Phố Huế - Bạch Mai"),
    (21.0077, 105.8427, HEADING_OMNI, CAT_CAM_RED_LIGHT, 50, FLAG_MOTO | FLAG_CAR | FLAG_REDLIGHT, "Cam AI Giải Phóng - Đại Cồ Việt (Lê Duẩn)"),
    (21.0033, 105.8203, HEADING_OMNI, CAT_CAM_PENALTY, 50, FLAG_MOTO | FLAG_CAR | FLAG_REDLIGHT, "Cam AI Ngã Tư Sở (Tây Sơn - Nguyễn Trãi - Láng)"),
    (21.0110, 105.8142, HEADING_OMNI, CAT_CAM_RED_LIGHT, 50, FLAG_MOTO | FLAG_CAR | FLAG_REDLIGHT, "Cam AI Thái Hà - Láng Hạ - Huỳnh Thúc Kháng"),
    (21.0171, 105.8124, HEADING_OMNI, CAT_CAM_PENALTY, 50, FLAG_MOTO | FLAG_CAR | FLAG_REDLIGHT, "Cam AI Giảng Võ - Láng Hạ / Cát Linh"),
    (21.0285, 105.8256, HEADING_OMNI, CAT_CAM_PENALTY, 50, FLAG_MOTO | FLAG_CAR | FLAG_REDLIGHT, "Cam AI Kim Mã - Nguyễn Thái Học - Sơn Tây"),
    (21.0309, 105.8147, HEADING_OMNI, CAT_CAM_PENALTY, 50, FLAG_MOTO | FLAG_CAR | FLAG_REDLIGHT, "Cam AI Liễu Giai - Kim Mã - Nguyễn Chí Thanh"),
    (21.0218, 105.8027, HEADING_OMNI, CAT_CAM_RED_LIGHT, 50, FLAG_MOTO | FLAG_CAR | FLAG_REDLIGHT, "Cam AI Nguyễn Chí Thanh - Trần Duy Hưng - Đường Láng"),
    (21.0074, 105.7995, HEADING_OMNI, CAT_CAM_PENALTY, 50, FLAG_MOTO | FLAG_CAR | FLAG_REDLIGHT, "Cam AI Lê Văn Lương - Hoàng Đạo Thúy / Láng"),
    (21.0014, 105.7929, HEADING_OMNI, CAT_CAM_PENALTY, 50, FLAG_MOTO | FLAG_CAR | FLAG_REDLIGHT, "Cam AI Khuất Duy Tiến - Lê Văn Lương - Tố Hữu"),
    (20.9923, 105.8006, HEADING_OMNI, CAT_CAM_PENALTY, 50, FLAG_MOTO | FLAG_CAR | FLAG_REDLIGHT, "Cam AI Ngã tư Nguyễn Xiển - Nguyễn Trãi (Khuất Duy Tiến)"),
    (20.9852, 105.8414, HEADING_OMNI, CAT_CAM_RED_LIGHT, 50, FLAG_MOTO | FLAG_CAR | FLAG_REDLIGHT, "Cam AI Giải Phóng - Kim Đồng / Giáp Bát"),
    (20.9636, 105.8427, HEADING_OMNI, CAT_CAM_PENALTY, 50, FLAG_MOTO | FLAG_CAR | FLAG_REDLIGHT, "Cam AI Nước Ngầm - Giải Phóng - Ngọc Hồi"),
    (21.0319, 105.7990, HEADING_OMNI, CAT_CAM_RED_LIGHT, 50, FLAG_MOTO | FLAG_CAR | FLAG_REDLIGHT, "Cam AI Cầu Giấy - Đường Láng - Bưởi"),
    (21.0366, 105.7825, HEADING_OMNI, CAT_CAM_RED_LIGHT, 50, FLAG_MOTO | FLAG_CAR | FLAG_REDLIGHT, "Cam AI Xuân Thủy - Phạm Hùng - Hồ Tùng Mậu (Cầu Vượt Mai Dịch)"),
    (21.0468, 105.7840, HEADING_OMNI, CAT_CAM_PENALTY, 50, FLAG_MOTO | FLAG_CAR | FLAG_REDLIGHT, "Cam AI Hoàng Quốc Việt - Phạm Văn Đồng"),
    (21.0169, 105.7805, HEADING_OMNI, CAT_CAM_PENALTY, 50, FLAG_MOTO | FLAG_CAR | FLAG_REDLIGHT, "Cam AI Phạm Hùng - Dương Đình Nghệ / Keangnam"),
    (21.0079, 105.7897, HEADING_OMNI, CAT_CAM_PENALTY, 50, FLAG_MOTO | FLAG_CAR | FLAG_REDLIGHT, "Cam AI BigC Trần Duy Hưng - Phạm Hùng - Khuất Duy Tiến"),
    (21.0559, 105.8070, HEADING_OMNI, CAT_CAM_SPEED, 60, FLAG_MOTO | FLAG_CAR, "Cam bắn tốc độ & sai làn Võ Chí Công (Gần cầu Nhật Tân)"),
    (21.0439, 105.8065, HEADING_OMNI, CAT_CAM_PENALTY, 50, FLAG_MOTO | FLAG_CAR, "Cam AI Võ Chí Công - Nguyễn Hoàng Tôn / Lạc Long Quân"),
    (21.0448, 105.8548, HEADING_OMNI, CAT_CAM_PENALTY, 50, FLAG_MOTO | FLAG_CAR, "Cam AI đầu Cầu Chương Dương (Trần Quang Khải - Hà Nội)"),
    (21.0365, 105.8594, HEADING_OMNI, CAT_CAM_RED_LIGHT, 50, FLAG_MOTO | FLAG_CAR | FLAG_REDLIGHT, "Cam AI Trần Nhật Duật - Hàng Đậu - Chương Dương"),
    (21.0144, 105.8624, HEADING_OMNI, CAT_CAM_PENALTY, 50, FLAG_MOTO | FLAG_CAR | FLAG_REDLIGHT, "Cam AI Trần Khát Chân - Lò Đúc / Nguyễn Khoái"),
    (20.9963, 105.8682, HEADING_OMNI, CAT_CAM_PENALTY, 50, FLAG_MOTO | FLAG_CAR | FLAG_REDLIGHT, "Cam AI Minh Khai - Vĩnh Tuy - Time City"),

    # --- CẢNH BÁO ĐƯỜNG CẤM XE MÁY (VÀNH ĐAI 3 TRÊN CAO, ĐL THĂNG LONG, CAO TỐC CT01) ---
    (20.9930, 105.7995, HEADING_OMNI, CAT_WARNING_SIGN, 50, FLAG_MOTO | FLAG_MOTO_PROHIBITED, "Cấm xe máy: Đường dẫn Vành Đai 3 trên cao (Khuất Duy Tiến)"),
    (21.0080, 105.7885, HEADING_OMNI, CAT_WARNING_SIGN, 50, FLAG_MOTO | FLAG_MOTO_PROHIBITED, "Cấm xe máy: Làn chính Đại Lộ Thăng Long / Lên Vành Đai 3"),
    (20.9862, 105.8758, HEADING_OMNI, CAT_WARNING_SIGN, 50, FLAG_MOTO | FLAG_MOTO_PROHIBITED, "Cấm xe máy: Nhánh cầu Thanh Trì trên cao (Lĩnh Nam -> Pháp Vân)"),
    (21.0195, 105.9150, HEADING_OMNI, CAT_WARNING_SIGN, 50, FLAG_MOTO | FLAG_MOTO_PROHIBITED, "Cấm xe máy: Lối vào Cao tốc Hà Nội - Hải Phòng (QL5B)"),
    (21.0354, 105.9255, HEADING_OMNI, CAT_WARNING_SIGN, 60, FLAG_MOTO | FLAG_MOTO_PROHIBITED, "Xe máy đi đường gom Cầu Phù Đổng - Tránh làn ô tô QL1A"),
    (21.2580, 106.1975, 40, CAT_WARNING_SIGN, 50, FLAG_MOTO | FLAG_MOTO_PROHIBITED, "Cấm xe máy: Cửa vào Cao tốc CT01 Bắc Giang - Lạng Sơn (Rẽ phải đường gom QL1A)"),
    (21.8110, 106.7425, 220, CAT_WARNING_SIGN, 50, FLAG_MOTO | FLAG_MOTO_PROHIBITED, "Cấm xe máy: Điểm cuối Cao tốc CT01 (TP Lạng Sơn - Chi Lăng)"),

    # --- HÀNH LANG TP. LẠNG SƠN -> ĐỒNG ĐĂNG -> QL4A -> THỊ TRẤN NA SẦM (VĂN LÃNG / LEWE2K6) ---
    (21.8685, 106.7640, HEADING_OMNI, CAT_TRAFFIC_LIGHT, 50, FLAG_MOTO | FLAG_CAR | FLAG_REDLIGHT, "Đèn đỏ Ngã tư Cao Lộc - Trần Đăng Ninh (Hướng Đồng Đăng)"),
    (21.8942, 106.7368, HEADING_OMNI, CAT_POLICE, 50, FLAG_MOTO | FLAG_CAR, "Điểm kiểm soát CSGT / Hạn chế tốc độ 50km/h Hoàng Đồng - QL1A"),
    (21.9258, 106.7095, HEADING_OMNI, CAT_WARNING_SIGN, 50, FLAG_MOTO | FLAG_CAR, "Giao cắt đường sắt & Dốc Đồng Đăng - QL1A"),
    (21.9461, 106.6968, HEADING_OMNI, CAT_CAM_PENALTY, 50, FLAG_MOTO | FLAG_CAR | FLAG_REDLIGHT, "Cam giám sát ngã ba TT Đồng Đăng (Rẽ trái vào QL4A đi Na Sầm)"),
    (21.9502, 106.6924, HEADING_OMNI, CAT_URBAN_SIGN, 50, FLAG_MOTO | FLAG_CAR, "Biển khu đông dân cư TT Đồng Đăng - đầu QL4A (Tối đa 50)"),
    (21.9765, 106.6780, HEADING_OMNI, CAT_WARNING_SIGN, 40, FLAG_MOTO | FLAG_CAR, "Đường đèo quanh co khuất tầm nhìn - QL4A (Hòa Bình / Thụy Hùng)"),
    (22.0080, 106.6530, HEADING_OMNI, CAT_WARNING_SIGN, 50, FLAG_MOTO | FLAG_CAR, "Dốc & cua gấp trên QL4A (Đoạn Thụy Hùng - Hoàng Việt)"),
    (22.0385, 106.6250, HEADING_OMNI, CAT_POLICE, 50, FLAG_MOTO | FLAG_CAR, "Chốt kiểm soát / Giảm tốc độ cửa ngõ TT Na Sầm (QL4A - Văn Lãng)"),
    (22.0548, 106.6128, HEADING_OMNI, CAT_URBAN_SIGN, 40, FLAG_MOTO | FLAG_CAR, "Trung tâm Thị trấn Na Sầm (Văn Lãng) - Chú ý tốc độ & chợ"),
]


def query_overpass(query: str, cache_name: str):
    cache_path = os.path.join(CACHE_DIR, cache_name)
    if os.path.exists(cache_path) and os.path.getsize(cache_path) > 10:
        with open(cache_path, "r", encoding="utf-8") as f:
            return json.load(f)

    data = urllib.parse.urlencode({"data": query}).encode("utf-8")
    last_err = None
    for url in OVERPASS_URLS:
        try:
            req = urllib.request.Request(
                url,
                data=data,
                headers={"User-Agent": "RoadPulseLite-Builder/1.0"}
            )
            with urllib.request.urlopen(req, timeout=45) as resp:
                raw = resp.read().decode("utf-8")
                parsed = json.loads(raw)
                with open(cache_path, "w", encoding="utf-8") as f:
                    json.dump(parsed, f, ensure_ascii=False)
                print(f"[OSM] Fetched {cache_name} from {url}: {len(parsed.get('elements', []))} elements")
                return parsed
        except Exception as e:
            last_err = e
            print(f"[OSM] Retry mirror failed for {cache_name} ({url}): {e}")
    print(f"[OSM] WARN: Failed to fetch {cache_name}: {last_err}")
    return {"elements": []}


def parse_maxspeed(val: str) -> int:
    if not val:
        return 0
    m = re.search(r"(\d{2,3})", str(val))
    if not m:
        return 0
    spd = int(m.group(1))
    if 20 <= spd <= 120:
        return spd
    return 0


def parse_direction_deg(tags: dict) -> int:
    for key in ("direction", "camera:direction"):
        raw = tags.get(key)
        if not raw:
            continue
        raw_s = str(raw).strip().upper()
        cardinals = {"N": 0, "NE": 45, "E": 90, "SE": 135, "S": 180, "SW": 225, "W": 270, "NW": 315}
        if raw_s in cardinals:
            return cardinals[raw_s]
        m = re.match(r"^(\d{1,3})", raw_s)
        if m:
            deg = int(m.group(1)) % 360
            return deg
    return HEADING_OMNI


def clean_label(s: str, max_bytes: int = 88) -> str:
    s = re.sub(r"\s+", " ", (s or "")).strip()
    enc = s.encode("utf-8")
    if len(enc) <= max_bytes:
        return s
    # Truncate cleanly on UTF-8 boundary
    trimmed = enc[:max_bytes].decode("utf-8", errors="ignore").rstrip()
    return trimmed + "…"


def add_poi(pois: list, dedup: set, lat, lon, heading, cat, speed, flags, label):
    if lat is None or lon is None:
        return
    try:
        lat_f = float(lat)
        lon_f = float(lon)
    except Exception:
        return
    if not (8.0 <= lat_f <= 23.6 and 102.0 <= lon_f <= 110.0):
        return
    # Dedup by (~33m bucket for cameras/lights, ~550m bucket for speed zone ways)
    scale = 180 if cat == CAT_URBAN_SIGN else 3000
    key = (round(lat_f * scale), round(lon_f * scale), int(cat))
    if key in dedup:
        return
    dedup.add(key)
    lat_e6 = int(round(lat_f * 1_000_000))
    lon_e6 = int(round(lon_f * 1_000_000))
    hdg = int(heading) if (0 <= int(heading) <= 359) else HEADING_OMNI
    spd = max(0, min(120, int(speed)))
    lbl = clean_label(label)
    if not lbl:
        lbl = "Cảnh báo giao thông"
    pois.append((lat_e6, lon_e6, hdg, cat, spd, flags, lbl))


def load_local_sources(pois: list, dedup: set):
    # 1. Fixed corridor cameras HN <-> Bac Ninh <-> Bac Giang <-> Lang Son
    cam_path = os.path.join(GSM_DIR, "vietmap_cameras_hanoi_langson.json")
    if os.path.exists(cam_path):
        with open(cam_path, "r", encoding="utf-8") as f:
            cam_data = json.load(f)
        for pt in cam_data.get("camera_clusters_both_directions", []):
            t = pt.get("type", "").lower()
            cat = CAT_CAM_PENALTY
            flags = FLAG_MOTO | FLAG_CAR | FLAG_TWO_WAY
            if "đèn đỏ" in pt.get("violations_monitored", "").lower():
                cat = CAT_CAM_RED_LIGHT
                flags |= FLAG_REDLIGHT
            elif "tốc độ" in t:
                cat = CAT_CAM_SPEED
            if "chốt csgt" in t or "trạm csgt" in t:
                cat = CAT_POLICE
            label = f"{pt.get('km') or pt.get('route')} ({pt.get('province', '')})"
            add_poi(pois, dedup, pt.get("lat"), pt.get("lon"), HEADING_OMNI, cat, pt.get("speed_limit_kmh", 50), flags, label)

    # 2. Nationwide LiveReports (447 reports)
    lr_path = os.path.join(GSM_DIR, "vietmap_livereports_nationwide.json")
    if os.path.exists(lr_path):
        with open(lr_path, "r", encoding="utf-8") as f:
            lr_data = json.load(f)
        for r in lr_data.get("reports", []):
            cname = (r.get("category_name") or "").strip().lower()
            desc = (r.get("description") or "").strip()
            spd = int(r.get("speed_limit") or 0)
            hdg = int(r.get("heading") or 0)
            ldir = int(r.get("line_direction") or 0)
            if ldir == 0 or hdg == 0:
                hdg_final = HEADING_OMNI
            else:
                hdg_final = hdg % 360

            flags = FLAG_MOTO | FLAG_CAR
            if "vi phạm" in cname:
                cat = CAT_POLICE
                label = desc or "Điểm thường xảy ra vi phạm / Kiểm tra"
            elif "khoảng cách" in cname:
                cat = CAT_ROAD_HAZARD
                label = desc or "Đoạn đường camera đo khoảng cách"
            elif "lối ra cao tốc" in cname:
                cat = CAT_ROAD_HAZARD
                label = desc or "Lối ra cao tốc"
            elif "nguy hiểm" in cname or "tai nạn" in cname or "đóng đường" in cname:
                cat = CAT_WARNING_SIGN
                label = desc or r.get("category_name") or "Cảnh báo nguy hiểm phía trước"
            elif "thi công" in cname or "đường xấu" in cname or "ổ gà" in cname or "kẹt xe" in cname:
                cat = CAT_ROAD_HAZARD
                label = desc or r.get("category_name") or "Đường xấu / Thi công"
            else:
                cat = CAT_ROAD_HAZARD
                label = desc or r.get("category_name") or "Cảnh báo giao thông"

            add_poi(pois, dedup, r.get("lat"), r.get("lon"), hdg_final, cat, spd, flags, label)

    # 3. Traffic light intersections with heading
    tl_path = os.path.join(GSM_DIR, "vietmap_traffic_lights.json")
    if os.path.exists(tl_path):
        with open(tl_path, "r", encoding="utf-8") as f:
            tl_data = json.load(f)
        for inter in tl_data.get("intersections", []):
            hdg = int(float(inter.get("heading") or 0)) % 360
            name = str(inter.get("name") or "")
            if name.isdigit():
                name = "Ngã tư đèn tín hiệu"
            add_poi(pois, dedup, inter.get("lat"), inter.get("lon"), hdg, CAT_TRAFFIC_LIGHT, 50, FLAG_MOTO | FLAG_CAR | FLAG_REDLIGHT, name)


def parse_osm_element(el, pois: list, dedup: set):
    tags = el.get("tags", {})
    lat = el.get("lat")
    lon = el.get("lon")
    if lat is None or lon is None:
        center = el.get("center") or {}
        lat, lon = center.get("lat"), center.get("lon")
    if lat is None or lon is None:
        return
    hdg = parse_direction_deg(tags)
    spd = parse_maxspeed(tags.get("maxspeed", ""))
    name = tags.get("name") or tags.get("ref") or ""

    if tags.get("highway") == "speed_camera" or tags.get("enforcement") in ("maxspeed", "average_speed"):
        label = f"Camera tốc độ {name}".strip()
        add_poi(pois, dedup, lat, lon, hdg, CAT_CAM_SPEED, spd or 50, FLAG_MOTO | FLAG_CAR, label)
    elif tags.get("enforcement") == "traffic_signals":
        label = f"Camera đèn đỏ {name}".strip()
        add_poi(pois, dedup, lat, lon, hdg, CAT_CAM_RED_LIGHT, 50, FLAG_MOTO | FLAG_CAR | FLAG_REDLIGHT, label)
    elif tags.get("man_made") == "surveillance":
        zone = (tags.get("surveillance:zone") or "").lower()
        surv = (tags.get("surveillance") or "").lower()
        if "traffic" not in zone and "traffic" not in surv:
            return
        label = f"Camera giao thông {name}".strip()
        add_poi(pois, dedup, lat, lon, hdg, CAT_CAM_PENALTY, spd or 50, FLAG_MOTO | FLAG_CAR, label)
    elif tags.get("barrier") == "toll_booth":
        label = f"Trạm thu phí {name}".strip()
        add_poi(pois, dedup, lat, lon, hdg, CAT_ROAD_HAZARD, 40, FLAG_CAR, label)
    elif "traffic_sign" in tags:
        ts = str(tags["traffic_sign"]).strip()
        if ts.lower() == "yes" and spd == 0 and not name:
            return
        if "city_limit" in ts or "maxspeed" in ts or spd > 0:
            label = f"Biển tốc độ {spd if spd else ''} {name}".strip()
            add_poi(pois, dedup, lat, lon, hdg, CAT_URBAN_SIGN, spd or 50, FLAG_MOTO | FLAG_CAR, label)
        else:
            ts_clean = ts.replace("VN:", "")
            if ts_clean.lower().startswith("p.102"):
                ts_clean = "Cấm đi ngược chiều (P.102)"
            elif ts_clean.lower().startswith("w.245"):
                ts_clean = "Đi chậm (W.245)"
            elif ts_clean.lower().startswith("w.227"):
                ts_clean = "Công trường phía trước (W.227)"
            label = f"Biển báo {ts_clean} {name}".strip()
            add_poi(pois, dedup, lat, lon, hdg, CAT_WARNING_SIGN, spd, FLAG_MOTO | FLAG_CAR, label)
    elif "hazard" in tags:
        label = f"Cảnh báo {tags['hazard']} {name}".strip()
        add_poi(pois, dedup, lat, lon, hdg, CAT_WARNING_SIGN, spd, FLAG_MOTO | FLAG_CAR, label)


def load_osm_sources(pois: list, dedup: set):
    # Query A1: Fast nationwide Vietnam speed_camera, enforcement, toll_booth
    q_vn_fast = """
    [out:json][timeout:30];
    (
      node["highway"="speed_camera"](8.2,102.1,23.5,109.6);
      node["enforcement"~"maxspeed|traffic_signals|average_speed|mindistance"](8.2,102.1,23.5,109.6);
      node["barrier"="toll_booth"](8.2,102.1,23.5,109.6);
      node["hazard"](8.2,102.1,23.5,109.6);
    );
    out body;
    """
    vn_data = query_overpass(q_vn_fast, "osm_vn_cameras_fast.json")
    for el in vn_data.get("elements", []):
        parse_osm_element(el, pois, dedup)

    # Query A2: Corridor traffic signs & traffic surveillance (Hanoi -> Na Sam)
    q_corr_signs = """
    [out:json][timeout:30];
    (
      node["traffic_sign"](20.90,105.68,22.18,106.88);
      node["man_made"="surveillance"]["surveillance"~"traffic|public"](20.90,105.68,22.18,106.88);
    );
    out body;
    """
    corr_signs = query_overpass(q_corr_signs, "osm_corridor_signs.json")
    for el in corr_signs.get("elements", []):
        parse_osm_element(el, pois, dedup)

    # Query B: Corridor Inner Hanoi <-> Bac Ninh <-> Bac Giang <-> Lang Son <-> Dong Dang <-> Na Sam (Lewe2k6)
    q_corridor_nodes = """
    [out:json][timeout:60];
    (
      node["highway"="traffic_signals"](20.92,105.70,22.15,106.85);
      node["railway"="level_crossing"](20.92,105.70,22.15,106.85);
      node["highway"="motorway_junction"](20.92,105.70,22.15,106.85);
    );
    out body;
    """
    corr_nodes = query_overpass(q_corridor_nodes, "osm_corridor_nodes.json")
    for el in corr_nodes.get("elements", []):
        tags = el.get("tags", {})
        lat, lon = el.get("lat"), el.get("lon")
        hdg = parse_direction_deg(tags)
        name = tags.get("name") or tags.get("ref") or ""
        if tags.get("highway") == "traffic_signals":
            label = f"Đèn tín hiệu {name}".strip()
            add_poi(pois, dedup, lat, lon, hdg, CAT_TRAFFIC_LIGHT, 50, FLAG_MOTO | FLAG_CAR | FLAG_REDLIGHT, label)
        elif tags.get("railway") == "level_crossing":
            label = f"Giao cắt đường sắt {name}".strip()
            add_poi(pois, dedup, lat, lon, HEADING_OMNI, CAT_WARNING_SIGN, 0, FLAG_MOTO | FLAG_CAR, label)
        elif tags.get("highway") == "motorway_junction":
            label = f"Nút giao / Lối ra {tags.get('exit_to') or name}".strip()
            add_poi(pois, dedup, lat, lon, HEADING_OMNI, CAT_ROAD_HAZARD, 0, FLAG_MOTO | FLAG_CAR, label)

    # Query C: Motorcycle-prohibited bridges/tunnels/ramps (motorcycle=no on non-parallel highway)
    # Plus speed-zone markers from cached osm_ways_north.json (spaced 550m apart via dedup)
    q_moto_prohibit = """
    [out:json][timeout:25];
    (
      way["highway"="motorway_link"](20.92,105.70,22.15,106.85);
      way["highway"~"trunk|primary|secondary"]["motorcycle"="no"](20.92,105.70,22.15,106.85);
    );
    out center;
    """
    # Write an empty cache marker if osm_moto_ramps isn't worth hanging Overpass; fetch with fast 8s timeout
    moto_cache = os.path.join(CACHE_DIR, "osm_moto_ramps.json")
    if not os.path.exists(moto_cache):
        try:
            data = urllib.parse.urlencode({"data": q_moto_prohibit}).encode("utf-8")
            req = urllib.request.Request(OVERPASS_URLS[0], data=data, headers={"User-Agent": "RoadPulseLite-Builder/1.0"})
            with urllib.request.urlopen(req, timeout=8) as resp:
                with open(moto_cache, "w", encoding="utf-8") as cf:
                    cf.write(resp.read().decode("utf-8"))
        except Exception:
            with open(moto_cache, "w", encoding="utf-8") as cf:
                cf.write('{"elements":[]}')
    wdata = query_overpass(q_moto_prohibit, "osm_moto_ramps.json")
    for el in wdata.get("elements", []):
        tags = el.get("tags", {})
        center = el.get("center") or {}
        lat, lon = center.get("lat"), center.get("lon")
        if lat is None or lon is None:
            continue
        name = tags.get("name") or tags.get("ref") or "Nhánh rẽ / Cầu vượt cấm xe máy"
        # Skip unnamed generic motorway_link spurs far from entries unless tagged explicitly
        if tags.get("highway") == "motorway_link" and tags.get("motorcycle") != "no" and not tags.get("name"):
            continue
        spd = parse_maxspeed(tags.get("maxspeed", ""))
        add_poi(pois, dedup, lat, lon, HEADING_OMNI, CAT_WARNING_SIGN, spd or 50, FLAG_MOTO | FLAG_MOTO_PROHIBITED, f"Cấm xe máy: {name}")

    north_cache = os.path.join(CACHE_DIR, "osm_ways_north.json")
    if os.path.exists(north_cache):
        with open(north_cache, "r", encoding="utf-8") as f:
            ndata = json.load(f)
        for el in ndata.get("elements", []):
            tags = el.get("tags", {})
            if tags.get("highway") == "motorway":
                continue
            center = el.get("center") or {}
            lat, lon = center.get("lat"), center.get("lon")
            if lat is None or lon is None:
                continue
            spd = parse_maxspeed(tags.get("maxspeed", ""))
            if spd in (30, 40, 50, 60):
                name = tags.get("name") or tags.get("ref") or "Tuyến chính"
                add_poi(pois, dedup, lat, lon, HEADING_OMNI, CAT_URBAN_SIGN, spd, FLAG_MOTO | FLAG_CAR, f"Tốc độ {spd} km/h • {name}")

    # Query D: HCMC traffic signals
    q_hcmc = """
    [out:json][timeout:45];
    node["highway"="traffic_signals"](10.68,106.58,10.88,106.82);
    out body;
    """
    hcmc_nodes = query_overpass(q_hcmc, "osm_hcmc_signals.json")
    for el in hcmc_nodes.get("elements", []):
        tags = el.get("tags", {})
        name = tags.get("name") or ""
        add_poi(pois, dedup, el.get("lat"), el.get("lon"), parse_direction_deg(tags), CAT_TRAFFIC_LIGHT, 50, FLAG_MOTO | FLAG_CAR | FLAG_REDLIGHT, f"Đèn tín hiệu {name}".strip())


def write_rpoi(pois: list, out_path: str):
    # Compute sparse grid index (0.05 deg = 500 * 1e-4 deg)
    cell_size_e4 = 500
    cell_size_e6 = cell_size_e4 * 100  # 50,000
    min_lat_e6 = 8_000_000
    min_lon_e6 = 102_000_000

    indexed = []
    for idx, p in enumerate(pois):
        lat_e6, lon_e6, hdg, cat, spd, flags, label = p
        row = max(0, min(65535, (lat_e6 - min_lat_e6) // cell_size_e6))
        col = max(0, min(65535, (lon_e6 - min_lon_e6) // cell_size_e6))
        cell_key = (row << 16) | col
        indexed.append((cell_key, lat_e6, lon_e6, hdg, cat, spd, flags, label, idx + 1))

    # Sort primarily by cell_key (unsigned), then lat, lon
    indexed.sort(key=lambda x: (x[0], x[1], x[2]))

    # Build sparse cell directory + string pool
    cells = []
    str_pool = bytearray()
    str_cache = {}
    records_bytes = bytearray()

    cur_cell = None
    cur_start = 0
    cur_count = 0

    for i, item in enumerate(indexed):
        cell_key, lat_e6, lon_e6, hdg, cat, spd, flags, label, poi_id = item
        if cell_key != cur_cell:
            if cur_cell is not None:
                cells.append((cur_cell, cur_start, cur_count))
            cur_cell = cell_key
            cur_start = i
            cur_count = 1
        else:
            cur_count += 1

        if label in str_cache:
            off, slen = str_cache[label]
        else:
            enc = label.encode("utf-8")
            off = len(str_pool)
            slen = len(enc)
            str_pool.extend(enc)
            str_cache[label] = (off, slen)

        tol = 180 if hdg == HEADING_OMNI else 36
        # Record = 24 bytes: <iiHBBBBHI I (Wait: check exact struct!)
        # <i (4) + i (4) + H (2) + B (1) + B (1) + B (1) + B (1) + H (2) + I (4) + I (4) = 24 bytes!
        records_bytes.extend(struct.pack(
            "<iiHBBBBHII",
            lat_e6, lon_e6, hdg, tol, cat, spd, flags, slen, off, poi_id
        ))

    if cur_cell is not None:
        cells.append((cur_cell, cur_start, cur_count))

    header_size = 40
    cell_table_size = len(cells) * 12
    records_offset = header_size + cell_table_size
    string_pool_offset = records_offset + len(records_bytes)

    cell_bytes = bytearray()
    for ckey, st, cnt in cells:
        cell_bytes.extend(struct.pack("<III", ckey, st, cnt))

    payload = bytearray()
    payload.extend(cell_bytes)
    payload.extend(records_bytes)
    payload.extend(str_pool)
    payload_crc32 = zlib.crc32(payload) & 0xFFFFFFFF

    header = struct.pack(
        "<4sHHiiIIIIII",
        b"RPOI",
        2,              # version 2 (with payload CRC32)
        cell_size_e4,   # 500 -> 0.05 deg
        min_lat_e6,
        min_lon_e6,
        len(cells),
        len(indexed),
        records_offset,
        string_pool_offset,
        int(time.time()),
        payload_crc32,
    )
    assert len(header) == 40, f"Header size {len(header)}"

    with open(out_path, "wb") as f:
        f.write(header)
        f.write(payload)

    # Stats
    from collections import Counter
    counts = Counter(p[3] for p in pois)
    print(f"Compiled {len(pois)} POIs across {len(cells)} grid cells -> {out_path} ({os.path.getsize(out_path):,} bytes)")
    print("Category breakdown:", dict(sorted(counts.items())))


def main():
    pois = []
    dedup = set()
    # Curated high-priority points first (take precedence in dedup buckets)
    for row in CURATED_POIS:
        add_poi(pois, dedup, *row)
    load_local_sources(pois, dedup)
    load_osm_sources(pois, dedup)

    out_path = os.path.join(ASSETS_DIR, "roadpulse_vn.rpoi")
    write_rpoi(pois, out_path)


if __name__ == "__main__":
    main()
