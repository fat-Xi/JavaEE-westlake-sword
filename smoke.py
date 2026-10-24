"""兼容旧测试入口；完整说明及测试实现见 check_app.py。"""
import runpy
from pathlib import Path

runpy.run_path(str(Path(__file__).with_name("check_app.py")), run_name="__main__")
