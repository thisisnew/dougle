import importlib.util
import io
import unittest
import zipfile
import tempfile
from pathlib import Path

spec = importlib.util.spec_from_file_location('trim', __file__.replace('-test.py', '.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class NativeTrimTest(unittest.TestCase):
    def test_preserves_every_retained_byte_and_license(self):
        source = io.BytesIO()
        values = {'LICENSE': b'notice', 'Reader.class': b'class-bytes', 'native/tokenizers.properties': b'version=0.33.0', 'native/osx-arm/lib.dylib': b'original-binary',
                  'native/osx-arm/lib.dSYM/debug': b'debug', 'native/linux/lib.so': b'foreign'}
        with zipfile.ZipFile(source, 'w') as archive:
            for name, content in values.items():
                archive.writestr(name, content)
        result, removed = module.trim_library(source.getvalue(), 'native/', 'native/osx-arm/')
        with zipfile.ZipFile(io.BytesIO(result)) as archive:
            self.assertEqual(set(archive.namelist()), {'LICENSE', 'Reader.class', 'native/tokenizers.properties', 'native/osx-arm/lib.dylib'})
            for name in archive.namelist():
                self.assertEqual(archive.read(name), values[name])
        self.assertEqual(removed, len(b'debugforeign'))

    def test_rejects_missing_platform(self):
        source = io.BytesIO()
        with zipfile.ZipFile(source, 'w') as archive:
            archive.writestr('LICENSE', b'notice')
        with self.assertRaises(ValueError):
            module.trim_library(source.getvalue(), 'native/', 'native/osx-arm/')

    def test_both_architectures_preserve_metadata_classes_and_stored_nested_jars(self):
        libraries = {
            'onnxruntime-test.jar': ('ai/onnxruntime/native/', 'osx-x64', 'osx-aarch64'),
            'tokenizers-test.jar': ('native/lib/', 'osx-x86_64', 'osx-aarch64'),
            'sqlite-jdbc-test.jar': ('org/sqlite/native/', 'Mac/x86_64', 'Mac/aarch64'),
        }
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / 'source.jar'
            with zipfile.ZipFile(source, 'w') as outer:
                outer.writestr('BOOT-INF/classes/App.class', b'application')
                for name, (prefix, intel, arm) in libraries.items():
                    content = io.BytesIO()
                    with zipfile.ZipFile(content, 'w') as inner:
                        inner.writestr(prefix + 'tokenizers.properties', b'version=0.33.0')
                        inner.writestr(prefix + intel + '/native.dylib', b'intel')
                        inner.writestr(prefix + arm + '/native.dylib', b'arm')
                        inner.writestr(prefix + 'linux/native.so', b'linux')
                        inner.writestr('LICENSE', b'license')
                    outer.writestr('BOOT-INF/lib/' + name, content.getvalue())
            original = source.read_bytes()
            for architecture, expected in [('x86_64', b'intel'), ('arm64', b'arm')]:
                target = Path(temporary) / (architecture + '.jar')
                module.trim(source, target, architecture)
                with zipfile.ZipFile(target) as outer:
                    self.assertEqual(outer.read('BOOT-INF/classes/App.class'), b'application')
                    for name, (prefix, intel, arm) in libraries.items():
                        entry = outer.getinfo('BOOT-INF/lib/' + name)
                        self.assertEqual(entry.compress_type, zipfile.ZIP_STORED)
                        with zipfile.ZipFile(io.BytesIO(outer.read(entry))) as inner:
                            self.assertEqual(inner.read(prefix + 'tokenizers.properties'), b'version=0.33.0')
                            native = [name for name in inner.namelist() if name.endswith('.dylib')]
                            self.assertEqual(len(native), 1)
                            self.assertEqual(inner.read(native[0]), expected)
                            self.assertEqual(inner.read('LICENSE'), b'license')
                            self.assertFalse(any(name.endswith('.so') for name in inner.namelist()))
            self.assertEqual(source.read_bytes(), original)


if __name__ == '__main__':
    unittest.main()
