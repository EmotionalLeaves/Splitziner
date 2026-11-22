"""Prints each failing test from Android test result files, without the stack-trace lines."""
import sys, glob, xml.etree.ElementTree as ET
for path in glob.glob("app/build/outputs/androidTest-results/connected/**/*.xml", recursive=True):
    for tc in ET.parse(path).getroot().iter("testcase"):
        for f in list(tc.iter("failure")) + list(tc.iter("error")):
            text = "\n".join(l for l in (f.text or "").splitlines() if not l.strip().startswith("at "))
            print(tc.get("name"), "->", text[:5000])
