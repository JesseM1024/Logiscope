# -*- coding: utf-8 -*-
import io, json

# ---- DataCommand.reset ----
p = "src/main/java/com/jessetech/logiscope/bind/DataCommand.java"
s = io.open(p, encoding="utf-8").read()
old = """    /** 测试/调试用：直接塞一段文本（离线断言与手填路径时用不到读取） */"""
new = """    /** 进世界时清残留：上一局的 NBT 文本与状态不应带到下一局（与 clearAll 同一时刻调用） */
    public static void reset() {
        text = "";
        status = Status.IDLE;
        waiting = false;
    }

    /** 测试/调试用：直接塞一段文本（离线断言与手填路径时用不到读取） */"""
assert old in s
s = s.replace(old, new, 1)
io.open(p, "w", encoding="utf-8", newline="\n").write(s)

# ---- lang ----
adds = [
    ("logiscope.btn.data_confirm", "确认添加", "Add"),
    ("logiscope.data.targets", "追踪目标", "Tracking targets"),
    ("logiscope.data.targets_hint", "回选格点「完成」后与容器格一起开始", "back to picker, press Done to start"),
    ("logiscope.flash.data_added", "已添加追踪：%s", "tracking added: %s"),
    ("logiscope.flash.data_dup", "该路径已在追踪中：%s", "already tracking: %s"),
]
for path, idx in [("src/main/resources/assets/logiscope/lang/zh_cn.json", 1),
                  ("src/main/resources/assets/logiscope/lang/en_us.json", 2)]:
    s2 = io.open(path, encoding="utf-8").read().rstrip()
    assert s2.endswith("}"), path
    inner = s2[1:-1].rstrip()
    if inner.endswith(","):
        inner = inner[:-1].rstrip()
    lines = [inner]
    for k, zh, en in adds:
        lines.append('  "%s": "%s"' % (k, zh if idx == 1 else en))
    out = "{\n" + ",\n".join(lines) + "\n}\n"
    io.open(path, "w", encoding="utf-8", newline="\n").write(out)
    json.load(io.open(path, encoding="utf-8"))
print("reset + lang ok")
