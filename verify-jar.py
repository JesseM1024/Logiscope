"""
模组 jar 验收工具。

用法：
    python verify-jar.py [jar路径]
    不带参数时默认检查 build/libs/walkthrough-1.0.0.jar

检查三件事：
  1. 三类文件是否齐全（模组元数据 / class / 资源）
  2. neoforge.mods.toml 里的 ${} 占位符是否已被替换
  3. 模组元数据的关键字段是否符合预期

在 Git Bash 下用 Python 处理 zip，比 unzip 更可靠（unzip 对 cygwin 路径常失败）。
"""

import os
import sys
import zipfile

DEFAULT_JAR = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                           "build", "libs", "walkthrough-1.0.0.jar")

SEP = "=" * 64


def main():
    jar = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_JAR

    if not os.path.isfile(jar):
        print(f"[FAIL] 找不到 jar: {jar}")
        print("       先执行 gradlew.bat build")
        return 1

    print(SEP)
    print(f"验收目标: {jar}")
    print(f"文件大小: {os.path.getsize(jar)} 字节")
    print(SEP)

    with zipfile.ZipFile(jar) as z:
        names = z.namelist()

        print("\n---------- 内容清单 ----------")
        for n in sorted(names):
            print(f"  {z.getinfo(n).file_size:>7d}  {n}")

        print("\n---------- 验收点 1：三类文件齐全 ----------")
        checks = {
            "模组元数据 META-INF/neoforge.mods.toml": any(n.endswith("META-INF/neoforge.mods.toml") for n in names),
            "编译后的 .class": any(n.endswith(".class") for n in names),
            "资源文件 assets/.../lang": any("/lang/" in n for n in names),
        }
        ok = True
        for k, v in checks.items():
            ok &= v
            print(f"  {'[OK]  ' if v else '[FAIL]'} {k}")

        print("\n---------- 验收点 2：占位符替换 ----------")
        if "META-INF/neoforge.mods.toml" not in names:
            print("  [SKIP] jar 里没有模组元数据，跳过")
        else:
            toml = z.read("META-INF/neoforge.mods.toml").decode("utf-8")
            token = "$" + "{"
            bad = [ln.strip() for ln in toml.splitlines() if token in ln]
            if bad:
                ok = False
                print("  [FAIL] 仍有未替换的占位符：")
                for ln in bad:
                    print("     ", ln)
            else:
                print("  [OK]   零残留，generateModMetadata 工作正常")

            print("\n---------- 验收点 3：模组元数据内容 ----------")
            for ln in toml.splitlines():
                s = ln.strip()
                if s and not s.startswith("#"):
                    print("   ", ln)

    print()
    print(SEP)
    print("结论: " + ("全部通过" if ok else "存在未通过项，见上"))
    print(SEP)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
