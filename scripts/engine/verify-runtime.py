#!/usr/bin/env python3
"""Inspect the packaged runtime with no UCRT64/MSYS2 directories on PATH."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
from bundle import ELEMENTS

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('runtime',type=Path)
args=parser.parse_args()
root=args.runtime.resolve()
system_root=Path(os.environ['SystemRoot'])
with tempfile.TemporaryDirectory(prefix='mirrordock-engine-check-') as temp:
    environment=dict(os.environ)
    environment['PATH']=os.pathsep.join((str(root/'bin'),str(system_root/'System32'),str(system_root)))
    environment['GST_PLUGIN_PATH_1_0']=str(root/'lib/gstreamer-1.0')
    environment['GST_PLUGIN_SYSTEM_PATH_1_0']=str(root/'lib/gstreamer-1.0')
    environment['GST_PLUGIN_SCANNER_1_0']=str(root/'libexec/gstreamer-1.0/gst-plugin-scanner.exe')
    environment['GST_REGISTRY_1_0']=str(Path(temp)/'registry.bin')
    environment['UXPLAYRC']=str(Path(temp)/'uxplayrc')
    Path(environment['UXPLAYRC']).write_text('# empty test configuration\n')
    for key in ('GST_PLUGIN_PATH','GST_PLUGIN_SYSTEM_PATH','GST_PLUGIN_SCANNER','GST_REGISTRY'):
        environment.pop(key,None)
    def run(command):
        result=subprocess.run(command,env=environment,cwd=temp,capture_output=True,text=True,encoding="utf-8",errors="replace",timeout=45)
        if result.returncode:
            raise RuntimeError(f'{Path(command[0]).name} exited {result.returncode}: {result.stdout}\n{result.stderr}')
        return result.stdout
    help_text=run([str(root/'bin/uxplay.exe'),'-h'])
    for option in ('-n name','-nh','-as','-d '):
        if option not in help_text:raise RuntimeError(f'UxPlay help is missing expected option {option}')
    for element in ELEMENTS:
        output=run([str(root/'bin/gst-inspect-1.0.exe'),element])
        if 'Plugin Details:' not in output or 'Filename' not in output:
            raise RuntimeError(f'Unexpected gst-inspect result for {element}')
    print(json.dumps({'uxplay_help':'passed','gstreamer_elements':list(ELEMENTS),'real_iphone_test':'not_performed'}))
