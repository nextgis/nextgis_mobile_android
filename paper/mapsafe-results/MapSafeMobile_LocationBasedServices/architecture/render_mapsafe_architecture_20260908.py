from pathlib import Path
from PIL import Image, ImageDraw, ImageFont


HERE = Path(__file__).resolve().parent
OUT = HERE / "mapsafe_nextgis_mobile_architecture_2026-09-08.png"

W, H = 2000, 1260
BG = "#FFFFFF"
INK = "#1F2933"
MUTED = "#52606D"
GREEN = "#256D35"
GREEN_FILL = "#EFF8F1"
BLUE = "#1D5FA7"
BLUE_FILL = "#EEF5FC"
AMBER = "#B7791F"
AMBER_FILL = "#FFF7DF"
PURPLE = "#6B46A5"
PURPLE_FILL = "#F5F0FB"
TEAL = "#167C80"
TEAL_FILL = "#EAF8F8"
GRAY = "#7B8794"
GRAY_FILL = "#F5F7F9"


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    name = "seguisb.ttf" if bold else "segoeui.ttf"
    candidates = [
        Path("C:/Windows/Fonts") / name,
        Path("C:/Windows/Fonts/arialbd.ttf" if bold else "C:/Windows/Fonts/arial.ttf"),
    ]
    for candidate in candidates:
        if candidate.exists():
            return ImageFont.truetype(str(candidate), size)
    return ImageFont.load_default()


F_TITLE = font(38, True)
F_SUBTITLE = font(19)
F_LANE = font(25, True)
F_NODE = font(20, True)
F_BODY = font(17)
F_SMALL = font(15)
F_LEGEND = font(15)


img = Image.new("RGB", (W, H), BG)
d = ImageDraw.Draw(img)


def rounded(box, fill, outline, width=3, radius=20):
    d.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def wrap(text, fnt, max_width):
    lines = []
    for paragraph in text.split("\n"):
        words = paragraph.split()
        if not words:
            lines.append("")
            continue
        line = words[0]
        for word in words[1:]:
            candidate = f"{line} {word}"
            if d.textlength(candidate, font=fnt) <= max_width:
                line = candidate
            else:
                lines.append(line)
                line = word
        lines.append(line)
    return lines


def centered_text(box, title, body, title_color=INK):
    x1, y1, x2, y2 = box
    max_width = x2 - x1 - 24
    title_lines = wrap(title, F_NODE, max_width)
    body_lines = wrap(body, F_BODY, max_width)
    title_h = len(title_lines) * 25
    body_h = len(body_lines) * 22
    gap = 10 if body else 0
    y = y1 + ((y2 - y1) - title_h - gap - body_h) / 2
    for line in title_lines:
        bbox = d.textbbox((0, 0), line, font=F_NODE)
        d.text(((x1 + x2 - (bbox[2] - bbox[0])) / 2, y), line, font=F_NODE, fill=title_color)
        y += 25
    y += gap
    for line in body_lines:
        bbox = d.textbbox((0, 0), line, font=F_BODY)
        d.text(((x1 + x2 - (bbox[2] - bbox[0])) / 2, y), line, font=F_BODY, fill=MUTED)
        y += 22


def node(box, title, body, fill=AMBER_FILL, outline=AMBER):
    rounded(box, fill, outline, width=3, radius=18)
    centered_text(box, title, body)


def arrow(start, end, color=INK, width=4, dashed=False):
    x1, y1 = start
    x2, y2 = end
    if dashed:
        dx, dy = x2 - x1, y2 - y1
        length = max((dx * dx + dy * dy) ** 0.5, 1)
        ux, uy = dx / length, dy / length
        pos = 0
        while pos < length - 18:
            seg = min(14, length - 18 - pos)
            d.line((x1 + ux * pos, y1 + uy * pos, x1 + ux * (pos + seg), y1 + uy * (pos + seg)), fill=color, width=width)
            pos += 24
    else:
        d.line((x1, y1, x2, y2), fill=color, width=width)
    import math
    angle = math.atan2(y2 - y1, x2 - x1)
    size = 16
    p1 = (x2 - size * math.cos(angle - 0.55), y2 - size * math.sin(angle - 0.55))
    p2 = (x2 - size * math.cos(angle + 0.55), y2 - size * math.sin(angle + 0.55))
    d.polygon([end, p1, p2], fill=color)


def poly_arrow(points, color=INK, width=4, dashed=False):
    for index in range(len(points) - 2):
        x1, y1 = points[index]
        x2, y2 = points[index + 1]
        if dashed:
            arrow((x1, y1), (x2, y2), color, width, True)
        else:
            d.line((x1, y1, x2, y2), fill=color, width=width)
    arrow(points[-2], points[-1], color, width, dashed)


def label(xy, text, color=MUTED, anchor="mm"):
    d.text(xy, text, font=F_SMALL, fill=color, anchor=anchor)


# Heading
d.text((W / 2, 36), "MapSafe Mobile architecture on the NextGIS platform", font=F_TITLE, fill=GREEN, anchor="ma")
d.text((W / 2, 85), "Implemented field-device, community-exchange, and filename-bound notarisation flows", font=F_SUBTITLE, fill=MUTED, anchor="ma")

# Main lanes
mobile_top = (55, 130, 1390, 590)
external = (1430, 130, 1945, 1115)
mobile_bottom = (55, 660, 1390, 1115)
rounded(mobile_top, GREEN_FILL, GREEN, 4, 28)
rounded(mobile_bottom, BLUE_FILL, BLUE, 4, 28)
rounded(external, GRAY_FILL, GRAY, 3, 28)
d.text((720, 150), "MapSafe Mobile — Safeguard", font=F_LANE, fill=GREEN, anchor="ma")
d.text((720, 680), "MapSafe Mobile — Access", font=F_LANE, fill=BLUE, anchor="ma")
d.text((1688, 150), "External services", font=F_LANE, fill=INK, anchor="ma")

# Safeguard nodes
n1 = (90, 215, 335, 370)
n2 = (375, 215, 620, 370)
n3 = (660, 215, 905, 370)
n4 = (930, 215, 1170, 370)
n5 = (1200, 215, 1360, 370)
node(n1, "Collect / select", "Original point dataset")
node(n2, "Anonymise (optional)", "Halo mask or H3 bin\nOriginal remains unchanged")
node(n3, "Select and encrypt", "Original, masked, or binned\nOne .pgp per dataset")
node(n4, "Notarise (optional)", "Bind encrypted filename\nto SHA-256")
node(n5, "Upload", "Keys, anonymised layers,\nor encrypted packages")
for a, b in [(n1, n2), (n2, n3), (n3, n4), (n4, n5)]:
    arrow((a[2], (a[1] + a[3]) / 2), (b[0], (b[1] + b[3]) / 2), GREEN)

# Local identity and security nodes
identity = (180, 425, 555, 545)
security = (620, 425, 1060, 545)
wallet = (1100, 425, 1360, 545)
node(identity, "Local OpenPGP identity", "Passphrase + Android Keystore\nPrivate key stays on device", PURPLE_FILL, PURPLE)
node(security, "Security & Sharing", "NextGIS account/group, key trust,\nsave folder, EVM profile", PURPLE_FILL, PURPLE)
node(wallet, "External wallet", "Reviews fee and signs transaction\nWallet key stays outside MapSafe", TEAL_FILL, TEAL)
arrow((555, 485), (660, 370), PURPLE, 3, True)
label((605, 420), "sign + recipients", PURPLE)
arrow((1060, 485), (945, 370), PURPLE, 3, True)
label((1000, 417), "network profile", PURPLE)
arrow((1190, 370), (1230, 425), TEAL, 3, True)

# External nodes
keys = (1470, 205, 1905, 385)
community = (1470, 430, 1905, 720)
chain = (1470, 770, 1905, 1045)
node(keys, "NextGIS public-key directory", "Authentication group\nMember public keys + metadata\nFingerprint acceptance remains local", GREEN_FILL, GREEN)
node(community, "NextGIS community resources", "Public keys\nHalo-masked / binned vector layers\nEncrypted .pgp packages\nSHA-256 + transaction metadata\nACL-controlled access", BLUE_FILL, BLUE)
node(chain, "EVM integrity record", "<encrypted filename>_<SHA-256>\nTransaction sender, chain, contract, time\nReceipt retrieved and checked", TEAL_FILL, TEAL)

# External connections
poly_arrow([(840, 425), (840, 395), (1390, 395), (1470, 295)], GREEN, 3, True)
label((1215, 382), "publish / synchronise public keys", GREEN)
poly_arrow([(1360, 292), (1390, 292), (1390, 560), (1470, 560)], BLUE, 4)
poly_arrow([(1360, 500), (1415, 500), (1415, 900), (1470, 900)], TEAL, 3, True)

# Access nodes
a5 = (90, 755, 335, 920)
a4 = (375, 755, 620, 920)
a3 = (660, 755, 905, 920)
a2 = (945, 755, 1190, 920)
a1 = (1225, 755, 1360, 920)
node(a1, "Choose package", "Community Packages\nor local .pgp")
node(a2, "Verify", "Local SHA-256\nRegistry and optional EVM check")
node(a3, "Decrypt", "Match local recipient key\nUnlock with passphrase")
node(a4, "Validate and import", "OpenPGP integrity/signature\nSave recovered GeoJSON")
node(a5, "Display", "Clear prior layers\nShow recovered dataset")
for a, b in [(a1, a2), (a2, a3), (a3, a4), (a4, a5)]:
    arrow((a[0], (a[1] + a[3]) / 2), (b[2], (b[1] + b[3]) / 2), BLUE)

recipient_identity = (585, 965, 980, 1075)
recipient_security = (1015, 965, 1360, 1075)
node(recipient_identity, "Local recipient identity", "Matching private key + passphrase\nPrivate key never uploaded", PURPLE_FILL, PURPLE)
node(recipient_security, "Community trust and access", "Synchronise keys/resources\nApply group membership and ACLs", PURPLE_FILL, PURPLE)
arrow((780, 965), (780, 920), PURPLE, 3, True)
label((800, 944), "matching private key", PURPLE, "lm")
arrow((1470, 600), (1360, 835), BLUE, 4)
poly_arrow([(1470, 905), (1410, 940), (1210, 940), (1190, 835)], TEAL, 3, True)

# Footer legend / boundary statement
rounded((180, 1160, 1820, 1225), "#FFFFFF", "#CBD2D9", 2, 16)
legend = (
    "Solid: protected-data/resource flow     Green dashed: NextGIS identity or key exchange     "
    "Purple dashed: local key/configuration use     Teal dashed: external-wallet and EVM verification"
)
d.text((1000, 1193), legend, font=F_LEGEND, fill=INK, anchor="mm")

img.save(OUT, format="PNG", optimize=True)
print(OUT)
