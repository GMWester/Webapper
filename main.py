#!/usr/bin/env python3
"""Entry point for WebApper (GUI by default, CLI with arguments)."""

import sys

from webapper.cli import main

if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
