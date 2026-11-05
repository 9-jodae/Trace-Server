"""내보내기 실패 시 기존 결과 보존을 확인한다."""

import importlib.util
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest import mock


def load_exporter():
    boto3 = types.ModuleType("boto3")
    boto3.client = mock.Mock()
    dotenv = types.ModuleType("dotenv")
    dotenv.dotenv_values = mock.Mock()
    psycopg = types.ModuleType("psycopg")
    pypdf = types.ModuleType("pypdf")
    pypdf.PdfReader = mock.Mock()
    dependencies = {"boto3": boto3, "dotenv": dotenv, "psycopg": psycopg, "pypdf": pypdf}
    spec = importlib.util.spec_from_file_location("document_exporter", Path(__file__).with_name("export-documents-json.py"))
    module = importlib.util.module_from_spec(spec)
    with mock.patch.dict(sys.modules, dependencies):
        spec.loader.exec_module(module)
    return module


class ExportFailureTest(unittest.TestCase):
    def test_invalid_documents_preserve_existing_output(self):
        exporter = load_exporter()
        for extension in ("docx", "md"):
            with self.subTest(extension=extension), tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / "export.json"
                output.write_text("previous", encoding="utf-8")
                connection = mock.MagicMock()
                cursor = connection.__enter__.return_value.cursor.return_value.__enter__.return_value
                cursor.fetchall.return_value = [("doc", "version", "title", extension, "key")]
                storage = mock.MagicMock()
                storage.get_object.return_value["Body"].__enter__.return_value.read.return_value = b""
                with (
                    mock.patch.object(exporter, "settings", return_value={"S3_BUCKET": "bucket"}),
                    mock.patch.object(exporter, "connection", return_value=connection),
                    mock.patch.object(exporter.boto3, "client", return_value=storage),
                    mock.patch.object(sys, "argv", ["export-documents-json.py", str(output)]),
                    mock.patch("builtins.print"),
                ):
                    with self.assertRaises(RuntimeError):
                        exporter.main()
                self.assertEqual("previous", output.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
