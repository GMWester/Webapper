"""Command line interface (also used for scripting / headless systems)."""

from __future__ import annotations

import argparse
import sys

from . import __version__
from .core import AppSpec, Builder, WebApperError
from .platforms import PLATFORM


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="webapper",
        description=f"WebApper {__version__} for {PLATFORM.display_name}: "
        "create Firefox-based web apps.",
    )
    sub = p.add_subparsers(dest="cmd")
    c = sub.add_parser("create", help="create a new web app")
    c.add_argument("url", help="website address, e.g. https://music.example.com")
    c.add_argument("--name", default="", help="app name (default: the page title)")
    c.add_argument(
        "--ext", action="append", default=[], metavar="LINK",
        help="Firefox add-on link (addons.mozilla.org page, slug, or .xpi URL); repeatable",
    )
    c.add_argument("--mode", choices=PLATFORM.supported_modes, default=PLATFORM.supported_modes[0])
    c.add_argument("--dest", default="", help="parent folder for portable apps")
    c.add_argument("--channel", choices=("release", "esr"), default="release")
    c.add_argument("--toolbar", action="store_true", help="show back/forward/reload buttons")
    c.add_argument("--no-addons-button", action="store_true",
                   help="hide the add-ons (puzzle) button used to configure add-ons")
    c.add_argument("--kiosk", action="store_true", help="always start full screen (kiosk)")
    c.add_argument("--no-desktop-shortcut", action="store_true")
    if PLATFORM.supports_steam:
        c.add_argument("--steam", action="store_true", help="add the app to the Steam library")
    sub.add_parser("list", help="list locally installed web apps")
    r = sub.add_parser("remove", help="remove a locally installed web app")
    r.add_argument("id")
    l = sub.add_parser("launch", help="start a locally installed web app")
    l.add_argument("id")
    sub.add_parser("gui", help="open the graphical installer (default)")
    return p


def main(argv: list[str]) -> int:
    args = build_parser().parse_args(argv)
    builder = Builder(PLATFORM, log=lambda m: print(m, flush=True))
    try:
        if args.cmd == "create":
            spec = AppSpec(
                name=args.name,
                url=args.url,
                extensions=args.ext,
                mode=args.mode,
                dest=args.dest,
                channel=args.channel,
                show_toolbar=args.toolbar,
                show_addons_button=not args.no_addons_button,
                kiosk=args.kiosk,
                desktop_shortcut=not args.no_desktop_shortcut,
                add_to_steam=getattr(args, "steam", False),
            )
            rec = builder.create(spec)
            print(f"Launcher: {rec.launcher}")
            return 0
        if args.cmd == "list":
            for r in builder.list_local():
                print(f"{r.id}\t{r.name}\t{r.url}")
            return 0
        if args.cmd in ("remove", "launch"):
            for r in builder.list_local():
                if r.id == args.id:
                    if args.cmd == "remove":
                        builder.remove(r)
                    else:
                        PLATFORM.launch(r)
                    return 0
            print(f"No app with id {args.id}", file=sys.stderr)
            return 1
    except WebApperError as e:
        print(f"Error: {e}", file=sys.stderr)
        return 1
    from .gui import run_gui

    return run_gui()
