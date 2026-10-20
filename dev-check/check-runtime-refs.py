r"""
检查 jar 里引用的每一个外部类，是否都能在运行时 classpath 里找到。

为什么需要它
------------
1.16.5 上 `org.slf4j` **编译期可见、运行期不可见**，于是 mod 编得过、打包也正常，
一进游戏就 `NoClassDefFoundError: org/slf4j/LoggerFactory` 崩在 `<clinit>`，
FML 判定 "has class loading errors"。javac 抓不到（编译期有），
verify-jar.py 也抓不到（它只看元数据和文件清单）。

判据来源
--------
运行时 classpath = `build/mcdeps/*.jar`（= `configurations.minecraft` 的全量），
这正是 FML 给 mod 的库集合。缺的类要么真的会崩，要么由 Forge 启动器另行提供
（见 RUNTIME_PROVIDED 白名单，逐条注明来源）。

用法
----
    python dev-check\check-runtime-refs.py build\libs\logiscope-<ver>.jar build\mcdeps
"""

import os
import struct
import sys
import zipfile

# 由 Forge 启动器（modlauncher / fmlcore / fmlloader）另行提供、不在
# `configurations.minecraft` 里的包。每条都要写清来源，别凭感觉加。
RUNTIME_PROVIDED = {
    # FML 自己的加载器实现，运行时由 forge-<ver>-universal / fmlloader 提供
    "net/minecraftforge/fml/loading/",
    # Dist 在 forgespi，IEventBus 在 eventbus —— 这两个 mcdeps 里其实有；
    # 留着前缀是为了将来 mcdeps 组成变化时不误报
    "net/minecraftforge/api/",
}

# ★ 反向黑名单：**开发 classpath 里有、生产运行时却没有**的包。
#
# 这条比"正向全解析"更要紧：`build/mcdeps` 是 `configurations.minecraft` 的副本，
# 它**天然比生产环境多东西**（FG5 为了 runClient 塞进来的库），所以正向检查永远
# 抓不到"dev 能跑、实机崩"的引用。
#
# 实测依据：`org/slf4j/LoggerFactory` 在 mcdeps 里有（slf4j-api-1.8.0-beta4），
# 但把它打进 jar 后，实机（HMCL 的 1.16.5-Forge 实例）启动即
#   NoClassDefFoundError: org/slf4j/LoggerFactory  (LogiScopeMod.<clinit>)
#   Failure message: LogiScope has class loading errors
# 根因：Forge 1.16.5 生产环境的类加载器只暴露它的 libraries 集合，
# slf4j-api 不在其中（只有 log4j-slf4j18-impl 这个绑定层）。
FORBIDDEN_PREFIXES = {
    "org/slf4j/": "1.16.5 生产运行时不可见（只有 log4j-slf4j18-impl 绑定层）；用 log4j2 的 LogManager",
}

SKIP_PREFIX = (
    "java/", "javax/", "jdk/", "sun/", "com/sun/",
    "com/jessetech/",          # 本项目自己的类
)


def class_refs(data):
    """解析 class 文件常量池，返回 CONSTANT_Class 指向的名字集合。"""
    count = struct.unpack(">H", data[8:10])[0]
    i = 10
    utf8 = {}
    class_idx = []
    idx = 1
    while idx < count:
        tag = data[i]
        i += 1
        if tag == 1:                                   # Utf8
            ln = struct.unpack(">H", data[i:i + 2])[0]
            i += 2
            utf8[idx] = data[i:i + ln].decode("utf-8", "replace")
            i += ln
        elif tag == 7:                                 # Class
            class_idx.append(struct.unpack(">H", data[i:i + 2])[0])
            i += 2
        elif tag in (8, 16, 19, 20):                   # String / MethodType / Module / Package
            i += 2
        elif tag == 15:                                # MethodHandle
            i += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):     # 数值 / 各种 ref / Dynamic
            i += 4
        elif tag in (5, 6):                            # Long / Double 占两个槽
            i += 8
            idx += 1
        else:
            raise ValueError("unknown constant pool tag %d at %d" % (tag, i))
        idx += 1
    return {utf8[k] for k in class_idx if k in utf8}


def main():
    jar = sys.argv[1]
    dep_dir = sys.argv[2]

    have = set()
    for f in sorted(os.listdir(dep_dir)):
        if not f.endswith(".jar"):
            continue
        try:
            with zipfile.ZipFile(os.path.join(dep_dir, f)) as z:
                have.update(n[:-6] for n in z.namelist() if n.endswith(".class"))
        except zipfile.BadZipFile:
            print("  [skip] 不是 zip: %s" % f)

    refs = set()
    with zipfile.ZipFile(jar) as z:
        own = {n[:-6] for n in z.namelist() if n.endswith(".class")}
        for n in z.namelist():
            if n.endswith(".class"):
                refs.update(class_refs(z.read(n)))
    have |= own

    forbidden = []
    for r in sorted(refs):
        for pre, why in FORBIDDEN_PREFIXES.items():
            if r.startswith(pre):
                forbidden.append((r, why))

    missing = []
    for r in sorted(refs):
        if r.startswith("["):                       # 数组描述符
            continue
        if any(r.startswith(p) for p in SKIP_PREFIX):
            continue
        if any(r.startswith(p) for p in RUNTIME_PROVIDED):
            continue
        if r not in have:
            missing.append(r)

    print("=" * 64)
    print("检查目标: %s" % jar)
    print("运行时类数: %d（build/mcdeps 全量 + 自身）" % len(have))
    print("外部类引用: %d" % len([r for r in refs if not r.startswith("[")
                                 and not any(r.startswith(p) for p in SKIP_PREFIX)]))
    print("-" * 64)
    if forbidden:
        print("结论: %d 个引用命中黑名单 —— 开发能跑、实机必崩" % len(forbidden))
        for r, why in forbidden:
            print("  FORBIDDEN  %s   (%s)" % (r, why))
        return 1
    if not missing:
        print("结论: 全部可解析（没有编得过却跑不了的依赖）")
        return 0

    print("结论: %d 个引用在运行时找不到 —— 进游戏会 NoClassDefFoundError" % len(missing))
    for m in missing:
        print("  MISSING  %s" % m)
    return 1


if __name__ == "__main__":
    sys.exit(main())
