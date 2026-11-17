"""Tk graphical installer: enter a web address + add-on links, pick an
install type, press Create."""

from __future__ import annotations

import queue
import threading
import traceback

from . import __version__
from .core import AppSpec, Builder, WebApperError
from .platforms import PLATFORM


def run_gui() -> int:
    try:
        import tkinter as tk
        from tkinter import filedialog, messagebox, ttk
    except ImportError:
        print("Tkinter is not available; use the command line: webapper create --help")
        return 1

    try:
        root = tk.Tk()
    except tk.TclError as e:
        print(f"Cannot open a window ({e}). Use the command line: webapper create --help")
        return 1

    root.title(f"WebApper {__version__} — {PLATFORM.display_name}")
    root.minsize(560, 620)
    events: "queue.Queue[tuple[str, object]]" = queue.Queue()

    frm = ttk.Frame(root, padding=16)
    frm.pack(fill="both", expand=True)
    frm.columnconfigure(1, weight=1)

    ttk.Label(frm, text="Create a Firefox-powered app from any website",
              font=("TkDefaultFont", 13, "bold")).grid(row=0, column=0, columnspan=3, sticky="w", pady=(0, 12))

    ttk.Label(frm, text="Website address").grid(row=1, column=0, sticky="w")
    url_var = tk.StringVar()
    url_entry = ttk.Entry(frm, textvariable=url_var)
    url_entry.grid(row=1, column=1, columnspan=2, sticky="ew", pady=4)
    url_entry.focus_set()

    ttk.Label(frm, text="App name").grid(row=2, column=0, sticky="w")
    name_var = tk.StringVar()
    ttk.Entry(frm, textvariable=name_var).grid(row=2, column=1, columnspan=2, sticky="ew", pady=4)
    ttk.Label(frm, text="(leave empty to use the page title)", foreground="gray").grid(
        row=3, column=1, columnspan=2, sticky="w")

    ttk.Label(frm, text="Firefox add-ons\n(one link per line)").grid(row=4, column=0, sticky="nw", pady=4)
    ext_text = tk.Text(frm, height=5, width=50, wrap="none")
    ext_text.grid(row=4, column=1, columnspan=2, sticky="nsew", pady=4)
    frm.rowconfigure(4, weight=1)
    ttk.Label(
        frm,
        text="e.g. https://addons.mozilla.org/firefox/addon/ublock-origin/  or a direct .xpi link",
        foreground="gray",
    ).grid(row=5, column=1, columnspan=2, sticky="w")

    ttk.Label(frm, text="Install type").grid(row=6, column=0, sticky="nw", pady=(10, 4))
    mode_var = tk.StringVar(value=PLATFORM.supported_modes[0])
    mode_box = ttk.Frame(frm)
    mode_box.grid(row=6, column=1, columnspan=2, sticky="w", pady=(10, 4))
    labels = {
        "local": "Local install (menu entry, shared Firefox runtime)",
        "portable": "Portable (self-contained folder you can move or copy)",
    }
    for m in PLATFORM.supported_modes:
        ttk.Radiobutton(mode_box, text=labels[m], value=m, variable=mode_var).pack(anchor="w")

    dest_var = tk.StringVar(value=PLATFORM.default_portable_parent())
    ttk.Label(frm, text="Portable folder").grid(row=7, column=0, sticky="w")
    dest_entry = ttk.Entry(frm, textvariable=dest_var)
    dest_entry.grid(row=7, column=1, sticky="ew", pady=4)

    def browse():
        d = filedialog.askdirectory(initialdir=dest_var.get() or None)
        if d:
            dest_var.set(d)

    dest_btn = ttk.Button(frm, text="Browse…", command=browse)
    dest_btn.grid(row=7, column=2, padx=(6, 0))

    opts = ttk.LabelFrame(frm, text="Options", padding=8)
    opts.grid(row=8, column=0, columnspan=3, sticky="ew", pady=8)
    toolbar_var = tk.BooleanVar(value=False)
    addons_var = tk.BooleanVar(value=True)
    kiosk_var = tk.BooleanVar(value=False)
    desk_var = tk.BooleanVar(value=True)
    steam_var = tk.BooleanVar(value=PLATFORM.supports_steam)
    esr_var = tk.BooleanVar(value=False)
    ttk.Checkbutton(opts, text="Show back / forward / reload buttons", variable=toolbar_var).grid(row=0, column=0, sticky="w")
    ttk.Checkbutton(opts, text="Always start full screen (kiosk)", variable=kiosk_var).grid(row=0, column=1, sticky="w")
    ttk.Checkbutton(opts, text="Add-ons button (configure add-ons)", variable=addons_var).grid(row=1, column=0, sticky="w")
    ttk.Checkbutton(opts, text="Use Firefox ESR", variable=esr_var).grid(row=1, column=1, sticky="w")
    if PLATFORM.supports_desktop_shortcut:
        ttk.Checkbutton(opts, text="Desktop shortcut (local installs)", variable=desk_var).grid(row=2, column=0, sticky="w")
    if PLATFORM.supports_steam:
        ttk.Checkbutton(opts, text="Add to Steam library", variable=steam_var).grid(row=2, column=1, sticky="w")

    def on_mode(*_):
        st = "normal" if mode_var.get() == "portable" else "disabled"
        dest_entry.configure(state=st)
        dest_btn.configure(state=st)

    mode_var.trace_add("write", on_mode)
    on_mode()

    create_btn = ttk.Button(frm, text="Create")
    create_btn.grid(row=9, column=0, columnspan=3, sticky="ew", pady=(4, 8), ipady=4)
    progress = ttk.Progressbar(frm, mode="indeterminate")
    progress.grid(row=10, column=0, columnspan=3, sticky="ew")
    log_box = tk.Text(frm, height=7, state="disabled", wrap="word")
    log_box.grid(row=11, column=0, columnspan=3, sticky="nsew", pady=(8, 0))
    frm.rowconfigure(11, weight=1)

    # installed apps
    apps_frame = ttk.LabelFrame(frm, text="Installed apps (local)", padding=8)
    apps_frame.grid(row=12, column=0, columnspan=3, sticky="nsew", pady=(10, 0))
    apps_frame.columnconfigure(0, weight=1)
    apps_list = tk.Listbox(apps_frame, height=4)
    apps_list.grid(row=0, column=0, rowspan=2, sticky="nsew")
    builder = Builder(PLATFORM, log=lambda m: events.put(("log", m)))
    records: list = []

    def refresh():
        records.clear()
        records.extend(builder.list_local())
        apps_list.delete(0, "end")
        for r in records:
            apps_list.insert("end", f"{r.name}  —  {r.url}")

    def selected():
        sel = apps_list.curselection()
        return records[sel[0]] if sel else None

    def do_launch():
        r = selected()
        if r:
            PLATFORM.launch(r)

    def do_remove():
        r = selected()
        if r and messagebox.askyesno("Remove app", f"Remove '{r.name}' and all its data?"):
            builder.remove(r)
            refresh()

    ttk.Button(apps_frame, text="Launch", command=do_launch).grid(row=0, column=1, padx=(6, 0), sticky="ew")
    ttk.Button(apps_frame, text="Remove", command=do_remove).grid(row=1, column=1, padx=(6, 0), sticky="ew")

    def log(msg: str):
        log_box.configure(state="normal")
        log_box.insert("end", msg + "\n")
        log_box.see("end")
        log_box.configure(state="disabled")

    def worker(spec: AppSpec):
        try:
            rec = builder.create(spec)
            events.put(("done", rec))
        except WebApperError as e:
            events.put(("error", str(e)))
        except Exception as e:  # pragma: no cover - surfaced to the user
            events.put(("error", f"{e}\n\n{traceback.format_exc()}"))

    def on_create():
        if not url_var.get().strip():
            messagebox.showerror("WebApper", "Please enter a website address.")
            return
        spec = AppSpec(
            name=name_var.get(),
            url=url_var.get(),
            extensions=ext_text.get("1.0", "end"),
            mode=mode_var.get(),
            dest=dest_var.get(),
            channel="esr" if esr_var.get() else "release",
            show_toolbar=toolbar_var.get(),
            show_addons_button=addons_var.get(),
            kiosk=kiosk_var.get(),
            desktop_shortcut=desk_var.get(),
            add_to_steam=steam_var.get(),
        )
        create_btn.configure(state="disabled")
        progress.start(12)
        log_box.configure(state="normal")
        log_box.delete("1.0", "end")
        log_box.configure(state="disabled")
        threading.Thread(target=worker, args=(spec,), daemon=True).start()

    create_btn.configure(command=on_create)

    def pump():
        try:
            while True:
                kind, payload = events.get_nowait()
                if kind == "log":
                    log(str(payload))
                    continue
                progress.stop()
                create_btn.configure(state="normal")
                if kind == "done":
                    rec = payload
                    refresh()
                    if messagebox.askyesno("WebApper", f"'{rec.name}' was created.\n\nLaunch it now?"):
                        PLATFORM.launch(rec)
                else:
                    log("Error: " + str(payload))
                    messagebox.showerror("WebApper", str(payload).split("\n\n")[0])
        except queue.Empty:
            pass
        root.after(100, pump)

    refresh()
    root.after(100, pump)
    root.mainloop()
    return 0
