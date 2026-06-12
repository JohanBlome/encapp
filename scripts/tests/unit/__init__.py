import sys
import os

MODULE_PATH = os.path.dirname(__file__)
ENCAPP_SCRIPTS_DIR = os.path.abspath(
    os.path.join(MODULE_PATH, os.pardir, os.pardir)
)
# insert(0), not append: a stale PYTHONPATH entry pointing at a sibling
# worktree's scripts/ dir would otherwise shadow the in-tree modules and
# tests would silently run against the wrong code.
if ENCAPP_SCRIPTS_DIR not in sys.path[:1]:
    sys.path.insert(0, ENCAPP_SCRIPTS_DIR)
