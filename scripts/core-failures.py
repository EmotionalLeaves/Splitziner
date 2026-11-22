"""Prints each failing core (JVM) test with its message."""
import glob, xml.etree.ElementTree as ET
for path in glob.glob("core/build/test-results/test/*.xml"):
    for tc in ET.parse(path).getroot().iter("testcase"):
        for f in list(tc.iter("failure")) + list(tc.iter("error")):
            print(tc.get("classname"), tc.get("name"), "->", (f.get("message") or "")[:600])
