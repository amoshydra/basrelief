#!/usr/bin/env python3
"""The node's half of the shared `contract/` fixtures.

Run from anywhere, in a container and with no ComfyUI, torch, numpy or pytest:

    podman run --rm -v "$PWD:/w:ro" -w /w python:3.12-slim \
        python3 server/comfy-node/tests/test_contract.py

The module under test is loaded BY PATH rather than imported as
`basrelief_depth.contract`, because importing the package runs
`basrelief_depth/__init__.py`, which imports nodes.py, which imports torch and
ComfyUI. The contract tests must be runnable with nothing installed -- that is
what lets this be the one part of the node CI can run cheaply.

Every expectation comes from `contract/`, which the Java app's tests read as
well. If this test and `TagContractTest`/`SplatSidecarContractTest` disagree, the
fixture is the tie-breaker and one of the two implementations is wrong.
"""

import importlib.util
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
CONTRACT = ROOT / "contract"

_spec = importlib.util.spec_from_file_location(
    "basrelief_contract", ROOT / "server/comfy-node/basrelief_depth/contract.py")
rules = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(rules)


def load(*parts):
    with open(CONTRACT.joinpath(*parts), encoding="utf-8") as fh:
        return json.load(fh)


class TagContractTest(unittest.TestCase):
    """contract/tag/cases.json — the string both sides derive filenames from."""

    def setUp(self):
        self.doc = load("tag", "cases.json")

    def test_everyCaseInTheTableIsTheRule(self):
        for case in self.doc["cases"]:
            with self.subTest(id=case["id"][:24]):
                self.assertEqual(case["tag"], rules.clean_tag(case["id"]), case["why"])

    def test_theTableCoversTheCasesItClaimsTo(self):
        # A fixture table that silently lost its hardest rows is a rule with no
        # guard, so the classes of case are asserted rather than described.
        ids = [c["id"] for c in self.doc["cases"]]
        self.assertTrue(any(len(i) > 48 for i in ids), "no over-long id")
        self.assertTrue(any(i == "" for i in ids), "no empty id")
        self.assertTrue(any(ord(ch) > 0xffff for i in ids for ch in i),
                        "no non-BMP character")
        self.assertTrue(any(ord(ch) > 0x7f for i in ids for ch in i),
                        "no non-ASCII character")
        self.assertTrue(any(i.startswith("-") or i.endswith("-") for i in ids),
                        "no leading/trailing dash")

    def test_truncationNeverSplitsACharacter(self):
        # The old divergence in one assertion: 48 kept CHARACTERS, and every one
        # of them ASCII, so the answer is valid UTF-8 and round-trips unchanged.
        for case in self.doc["cases"]:
            tag = rules.clean_tag(case["id"])
            self.assertLessEqual(len(tag), self.doc["maxChars"])
            self.assertEqual(tag, tag.encode("utf-8").decode("utf-8"))
            self.assertTrue(all(ord(c) < 0x80 for c in tag))

    def test_theRuleIsIdempotent(self):
        # Re-sanitising an already-sanitised tag is what the node does when the
        # app has already run the rule, so it must change nothing.
        for case in self.doc["cases"]:
            once = rules.clean_tag(case["id"])
            self.assertEqual(once, rules.clean_tag(once))

    def test_theFallbackIsTheNodesPrivatePolicyAndOnlyForAnEmptyAnswer(self):
        self.assertEqual("geo", rules.safe_tag("///", fallback="geo"))
        self.assertEqual("", rules.clean_tag("///"))
        self.assertEqual("abc", rules.safe_tag("a b/c", fallback="geo"))
        self.assertNotEqual("", rules.safe_tag("", fallback=None))

    def test_theFilenamesBothSidesDeriveFromTheTag(self):
        names = self.doc["names"]
        self.assertEqual(names["tag"], rules.clean_tag(names["id"]))
        self.assertEqual(
            (names["stream"]["depth"], names["stream"]["normal"],
             names["stream"]["color"], names["stream"]["progress"]),
            rules.stream_names(names["tag"]))
        self.assertEqual((names["splat"]["cloud"], names["splat"]["sidecar"]),
                         rules.splat_names(names["tag"]))


class SidecarContractTest(unittest.TestCase):
    """contract/sidecar/ — the node writes it; the app reads focal/width/height."""

    def test_theWriterReproducesTheGolden(self):
        # The inputs are written HERE, not read out of the golden, or the test
        # would only prove that a dict equals itself.
        golden = load("sidecar", "splat-sidecar.json")
        written = rules.splat_sidecar(
            splat_name="basrelief_demo-item.splat",
            splats=154000, selected=149504, padding=4496,
            focal=800, internal_size=1536, budget=150000,
            width=1200, height=1600, tag="demo-item")
        self.assertEqual(golden, written)

    def test_theReaderCasesNameTheFieldsTheAppReads(self):
        doc = load("sidecar", "cases.json")
        for case in doc["cases"]:
            with self.subTest(file=case["file"]):
                for field in ("focal", "width", "height", "lens"):
                    if not case.get("unparseable"):
                        self.assertIn(field, case)

    def test_theGoldenIsTheNodeShapeWithAnIntegerFocal(self):
        golden = load("sidecar", "splat-sidecar.json")
        self.assertEqual(
            {"state", "splat", "splats", "selected", "padding", "focal",
             "internal_size", "budget", "width", "height", "tag"},
            set(golden))
        # The app reads focal with optDouble and rounds it; an int in the file is
        # what the node promises, and 0 (not 0.0, not absent) is its "fit failed".
        self.assertIsInstance(golden["focal"], int)
        self.assertIsInstance(golden["height"], int)
        self.assertEqual(800, golden["focal"])
        self.assertEqual(1600, golden["height"])


class NoSecondCopyTest(unittest.TestCase):
    """The rule has one home in Python: this module, and nodes.py imports it."""

    def test_nodesDoesNotRunItsOwnTagRule(self):
        source = (ROOT / "server/comfy-node/basrelief_depth/nodes.py").read_text(
            encoding="utf-8")
        self.assertNotIn("isalnum(", source,
                         "nodes.py has its own tag rule again — the fixture only "
                         "holds if there is one implementation")
        self.assertIn("from .contract import", source)


if __name__ == "__main__":
    unittest.main(verbosity=2)
