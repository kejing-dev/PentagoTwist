"""Text / icon colour x background pairs of the theme (D13): WCAG contrast ratios, a markdown table for ui-audit.md.
Glass = the darkest and lightest window glass measured on the emulator (#B4B3B1 / #E6E6E4)."""
def lin(c):
    c /= 255
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
def L(h):
    r, g, b = (int(h[i:i + 2], 16) for i in (1, 3, 5))
    return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
def cr(a, b):
    x, y = sorted((L(a), L(b)), reverse=True)
    return (x + 0.05) / (y + 0.05)
def lab_l(h):
    y = L(h)
    return 116 * (y ** (1 / 3) if y > 0.008856 else 7.787 * y + 16 / 116) - 16
PAIRS = [
    ("正文 Ink", "#1B1F23", "玻璃暗 #B4B3B1", "#B4B3B1", 4.5), ("正文 Ink", "#1B1F23", "玻璃亮 #E6E6E4", "#E6E6E4", 4.5),
    ("小字 InkSecondary", "#3A3F44", "玻璃暗", "#B4B3B1", 4.5), ("小字 InkSecondary", "#3A3F44", "卡片 TagFace", "#FBF6EC", 4.5),
    ("状态行 Ink", "#1B1F23", "卡片 TagFace", "#FBF6EC", 4.5), ("警示 ErrorText", "#7A1A18", "卡片 TagFace", "#FBF6EC", 4.5),
    ("主按钮白字", "#FFFFFF", "Primary #1D7268", "#1D7268", 4.5), ("危险键白字（退出 / 确认）", "#FFFFFF", "Error #B8322F", "#B8322F", 4.5),
    ("转盘键白字", "#FFFFFF", "Primary #1D7268", "#1D7268", 4.5), ("提示中的转盘键墨字", "#1B1F23", "Teal3D #2FA69A", "#2FA69A", 4.5),
    ("灰转盘键字", "#3A3F44", "#E3DED6", "#E3DED6", 4.5), ("规则序号白字", "#FFFFFF", "Primary", "#1D7268", 4.5),
    ("小牌字 Ink", "#1B1F23", "小牌 TagFace", "#FBF6EC", 4.5), ("小牌警示 ErrorText", "#7A1A18", "小牌 TagFace", "#FBF6EC", 4.5),
    ("面板黑珠（图标）", "#141418", "坑 WalnutDeep #3E2816", "#3E2816", 1.0), ("面板黑珠的枫木边（图标）", "#D9B88A", "坑 WalnutDeep", "#3E2816", 3.0),
    ("面板白珠（图标）", "#F2EEE6", "坑 WalnutDeep", "#3E2816", 3.0), ("面板胡桃块 vs 玻璃（图标）", "#6E4A2B", "玻璃暗", "#B4B3B1", 3.0),
]
print("| 文字 / 图标 | 前景 | 底色 | 对比度 | 要求 | 结论 |\n|---|---|---|---|---|---|")
for name, fg, bgname, bg, need in PAIRS:
    r = cr(fg, bg)
    print(f"| {name} | {fg} | {bgname} {bg} | {r:.2f}:1 | ≥ {need}:1 | {'过' if r >= need else '不过'} |")
print("\n3D 设计色 ΔL*（棋类模板：双方 ≥ 30；珠 vs 盘、物件 vs 盘 ≥ 25 或色相明显不同）：")
for a, an, b, bn in [("#141418", "黑曜珠", "#F2EEE6", "珍珠珠"), ("#141418", "黑曜珠", "#6E4A2B", "胡桃转盘"), ("#F2EEE6", "珍珠珠", "#6E4A2B", "胡桃转盘"),
                     ("#6E4A2B", "胡桃转盘", "#D9B88A", "枫木框"), ("#2FA69A", "青色手柄", "#D9B88A", "枫木框"), ("#6E4A2B", "胡桃托盘", "#D9B88A", "枫木框"), ("#D99A2B", "琥珀边", "#6E4A2B", "胡桃转盘")]:
    print(f"- {an} {a} L*={lab_l(a):.0f} vs {bn} {b} L*={lab_l(b):.0f}：ΔL* = {abs(lab_l(a) - lab_l(b)):.0f}")
