# -*- coding: utf-8 -*-
"""
build.py —— 在线购物系统（第 5 组）构建脚本
====================================================
作用：一条命令完成「校验依赖 → 跑全部自动化用例 → 打包交付压缩包」，产出
    dist/online-shopping-group5-1.0.0.zip

用法（在仓库根目录执行）：
    python build.py              # 跑测试 + 打包
    python build.py --skip-test  # 只打包（不建议）

说明：
- 构建工具为 Python 3.10+ 与 pip（依赖清单 requirements.txt）
- 测试依赖见 requirements-dev.txt（httpx2 为 fastapi TestClient 所需）
- 构造的 zip 不含 .git、虚拟环境、缓存、数据库与 dist 自身，
  解压后即为《系统验收标准》要求的「项目简写名称-版本号.zip」
"""

import argparse
import os
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

PROJECT = "online-shopping-group5"
VERSION = "1.0.0"
ROOT = Path(__file__).resolve().parent
DIST = ROOT / "dist"
ZIP_PATH = DIST / f"{PROJECT}-{VERSION}.zip"

TEST_SCRIPTS = [
    ("冒烟测试（主流程 30 项）", "test_smoke.py"),
    ("黄程宇模块自测（18 项）", "test_b_module.py"),
    ("周到模块自测（10 项）", "test_d_module.py"),
    ("交叉测试（验收清单 14 项）", "test_cross_review.py"),
]

SKIP_DIRS = {".git", ".github", "dist", "__pycache__", "venv", ".venv", ".idea", ".vscode"}
SKIP_SUFFIX = {".pyc", ".pyo", ".db", ".db-wal", ".db-shm"}


def run_tests() -> None:
    """按顺序执行四套自动化用例，任一失败即中断构建。"""
    print("\n[1/3] 执行自动化测试用例")
    print("-" * 56)
    failed = []
    for title, script in TEST_SCRIPTS:
        path = ROOT / script
        if not path.exists():
            print(f"  跳过 {script}：文件不存在")
            continue
        print(f"\n  >>> {title}  ({script})")
        proc = subprocess.run([sys.executable, script], cwd=ROOT)
        if proc.returncode != 0:
            failed.append(script)
    print("-" * 56)
    if failed:
        print(f"构建中止：以下用例未通过 -> {', '.join(failed)}")
        sys.exit(1)
    print("测试全部通过。")


def collect_files():
    """收集需要打进交付包的文件（相对路径）。"""
    files = []
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            p = Path(dirpath) / name
            if p.suffix.lower() in SKIP_SUFFIX:
                continue
            if name == ZIP_PATH.name:
                continue
            files.append(p.relative_to(ROOT))
    return sorted(files)


def package() -> None:
    """生成 dist/online-shopping-group5-1.0.0.zip。"""
    print("\n[2/3] 收集交付文件")
    files = collect_files()
    print(f"  共 {len(files)} 个文件")

    print("\n[3/3] 打包")
    DIST.mkdir(exist_ok=True)
    if ZIP_PATH.exists():
        ZIP_PATH.unlink()
    with zipfile.ZipFile(ZIP_PATH, "w", zipfile.ZIP_DEFLATED) as z:
        for rel in files:
            z.write(ROOT / rel, Path(f"{PROJECT}-{VERSION}") / rel)

    size_kb = ZIP_PATH.stat().st_size / 1024
    print("-" * 56)
    print(f"构建成功：{ZIP_PATH.relative_to(ROOT)}  ({size_kb:.1f} KB)")
    print("解压后得到项目目录，按《部署手册》或镜像说明启动。")


def main() -> None:
    parser = argparse.ArgumentParser(description="在线购物系统（第5组）构建脚本")
    parser.add_argument("--skip-test", action="store_true", help="跳过自动化测试直接打包")
    args = parser.parse_args()

    print("=" * 56)
    print(f"构建 {PROJECT} v{VERSION}")
    print(f"Python {sys.version.split()[0]}  |  项目目录 {ROOT}")
    print("=" * 56)

    if args.skip_test:
        print("\n[1/3] 已跳过测试（--skip-test）")
    else:
        run_tests()

    package()


if __name__ == "__main__":
    main()
