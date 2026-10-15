import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import numpy as np
from PIL import Image
from fastapi.testclient import TestClient

from main import app
from common import runtime
from camouflaged_object_detection import detector

ALGORITHM = "camouflaged_object_detection"


class ServiceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.source = self.root / "image.png"
        Image.new("RGB", (71, 53), (35, 120, 75)).save(self.source)
        self.weights = patch.object(runtime, "WEIGHTS_DIR", self.root / "weights")
        self.output = patch.object(runtime, "RESULT_DIR", self.root / "results")
        self.weights.start()
        self.output.start()
        detector.reload()
        self.client = TestClient(app)

    def tearDown(self):
        detector.reload()
        self.output.stop()
        self.weights.stop()
        self.temp.cleanup()

    def test_only_c2fnet_registered(self):
        data = self.client.get("/algorithms").json()
        self.assertEqual([item["algorithm"] for item in data["algorithms"]], [ALGORITHM])
        for retired in ("change_detection", "semantic_segmentation"):
            self.assertEqual(self.client.post("/inference/" + retired, json={"image_path": str(self.source)}).status_code, 404)

    def test_missing_weights_fail_without_files(self):
        response = self.client.post("/inference/" + ALGORITHM, json={"image_path": str(self.source)})
        self.assertEqual(response.status_code, 503)
        self.assertIn("weights missing", response.json()["detail"])
        self.assertFalse((self.root / "results").exists())
        self.assertEqual(self.client.get("/health").json()["status"], "DEGRADED")

    def test_invalid_image_fails(self):
        for path in (self.root / "absent.png", self.root / "broken.png"):
            if path.name == "broken.png":
                path.write_text("broken")
            response = self.client.post("/inference/" + ALGORITHM, json={"image_path": str(path)})
            self.assertEqual(response.status_code, 400)

    def test_second_image_is_rejected(self):
        self.assertEqual(self.client.post("/inference/" + ALGORITHM, json={
            "image_path": str(self.source), "image_path_b": str(self.source),
        }).status_code, 422)

    def test_no_torch_failure_is_not_simulated(self):
        with patch.object(runtime, "torch_module", return_value=None):
            with self.assertRaisesRegex(RuntimeError, "PyTorch"):
                detector._build(self.source)

    def test_reload_recovers_failed_cache(self):
        calls = []
        def builder(path):
            calls.append(path)
            if len(calls) == 1:
                raise RuntimeError("temporary dependency failure")
            return {"model": object()}
        cache = runtime.WeightedModelCache(("c2fnet.pth",), builder)
        self.assertIn("error", cache.get())
        self.assertIn("error", cache.get())
        self.assertEqual(len(calls), 1)
        cache.reset()
        self.assertIn("model", cache.get())

    def test_weights_replacement_and_deletion_rebuild_cache(self):
        directory = self.root / "weights"
        directory.mkdir()
        path = directory / "c2fnet.pth"
        calls = []
        def builder(weight):
            calls.append(weight)
            if weight is None:
                raise RuntimeError("missing")
            return {"model": object()}
        cache = runtime.WeightedModelCache(("c2fnet.pth",), builder)
        self.assertIn("error", cache.get())
        path.write_bytes(b"first")
        first = cache.get()["model"]
        self.assertIs(cache.get()["model"], first)
        path.write_bytes(b"replacement")
        self.assertIsNot(cache.get()["model"], first)
        path.unlink()
        self.assertIn("error", cache.get())
        self.assertEqual(len(calls), 4)

    def test_output_names_unique(self):
        mask = np.arange(53 * 71, dtype=np.uint8).reshape(53, 71)
        a = runtime.write_mask(mask, self.source, {}, "test")
        b = runtime.write_mask(mask, self.source, {}, "test")
        self.assertNotEqual(a, b)
        for paths in (a, b):
            for path in paths:
                with Image.open(path) as image:
                    np.testing.assert_array_equal(np.asarray(image), mask)

    def test_geotiff_reference_and_uint8_values_preserved(self):
        import rasterio
        from rasterio.transform import from_origin
        path = self.root / "geo.tif"
        transform = from_origin(120, 32, 0.01, 0.01)
        rgb = np.full((3, 53, 71), 120, dtype=np.uint8)
        with rasterio.open(path, "w", driver="GTiff", height=53, width=71,
                           count=3, dtype="uint8", crs="EPSG:4326", transform=transform) as out:
            out.write(rgb)
        loaded, meta = runtime.read_image_rgb(path)
        np.testing.assert_array_equal(loaded, rgb.transpose(1, 2, 0))
        tif, _ = runtime.write_mask(loaded[:, :, 0], path, meta, "test")
        with rasterio.open(tif) as result:
            self.assertEqual(result.crs.to_epsg(), 4326)
            self.assertEqual(result.transform, transform)
            self.assertEqual(result.count, 1)

    def test_uint16_tiff_requires_explicit_conversion(self):
        Image.fromarray(np.zeros((53, 71), dtype=np.uint16)).save(self.root / "uint16.tif")
        with self.assertRaisesRegex(ValueError, "uint8"):
            runtime.read_image_rgb(self.root / "uint16.tif")


@unittest.skipUnless(os.environ.get("C2FNET_TEST_WEIGHTS"), "Set C2FNET_TEST_WEIGHTS for real inference")
class RealModelTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.weight = Path(os.environ["C2FNET_TEST_WEIGHTS"]).resolve()
        cls.loaded = detector._build(cls.weight)

    def test_real_api_and_repeat_preservation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.png"
            Image.fromarray(np.random.default_rng(12).integers(0, 256, (53, 71, 3), dtype=np.uint8)).save(source)
            with patch.object(detector._cache, "get", return_value=self.loaded), patch.object(runtime, "RESULT_DIR", root / "results"):
                client = TestClient(app)
                first = client.post("/inference/" + ALGORITHM, json={"image_path": str(source)})
                second = client.post("/inference/" + ALGORITHM, json={"image_path": str(source)})
                self.assertEqual(first.status_code, 200, first.text)
                self.assertEqual(second.status_code, 200, second.text)
                a, b = first.json(), second.json()
                self.assertTrue(a["weights_loaded"])
                self.assertFalse(a["simulated"])
                self.assertEqual((a["width"], a["height"]), (71, 53))
                self.assertEqual(a["output_type"], "per_image_normalized_grayscale")
                self.assertEqual(len(a["weights_sha256"]), 64)
                self.assertNotEqual(a["mask_path"], b["mask_path"])
                with Image.open(a["preview_path"]) as im_a, Image.open(b["preview_path"]) as im_b:
                    np.testing.assert_array_equal(np.asarray(im_a), np.asarray(im_b))
                    self.assertGreater(len(np.unique(np.asarray(im_a))), 2)

    def test_incompatible_checkpoint_rejected(self):
        import torch
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "wrong.pth"
            torch.save({"wrong_parameter": torch.zeros(1)}, path)
            with self.assertRaises(RuntimeError):
                detector._build(path)


if __name__ == "__main__":
    unittest.main()
