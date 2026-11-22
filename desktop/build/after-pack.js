// Gives PhotoS.exe its own icon and name (Task Manager, Explorer, taskbar) instead of Electron's.
// Done with resedit, which edits Windows executables from any OS (electron-builder's own step needs Windows or Wine).
const fs = require('fs');
const path = require('path');

exports.default = async function afterPack(context) {
  if (context.electronPlatformName === 'darwin') return signMac(context);
  if (context.electronPlatformName !== 'win32') return;
  const { NtExecutable, NtExecutableResource, Data, Resource } = await import('resedit');
  const info = context.packager.appInfo;
  const exe = path.join(context.appOutDir, `${info.productFilename}.exe`);
  const executable = NtExecutable.from(fs.readFileSync(exe), { ignoreCert: true });
  const resources = NtExecutableResource.from(executable);

  const icon = Data.IconFile.from(fs.readFileSync(path.join(__dirname, 'icon.ico')));
  const groups = Resource.IconGroupEntry.fromEntries(resources.entries);
  const target = groups[0] ?? { id: 1, lang: 1033 };
  Resource.IconGroupEntry.replaceIconsForResource(resources.entries, target.id, target.lang, icon.icons.map((i) => i.data));

  const version = Resource.VersionInfo.fromEntries(resources.entries)[0];
  const parts = info.version.split('.').map((n) => parseInt(n, 10) || 0);
  while (parts.length < 4) parts.push(0);
  version.setFileVersion(...parts);
  version.setProductVersion(...parts);
  const strings = {
    FileDescription: info.productName, ProductName: info.productName, CompanyName: info.productName,
    InternalName: info.productName, OriginalFilename: `${info.productFilename}.exe`,
    FileVersion: info.version, ProductVersion: info.version, LegalCopyright: '',
  };
  const languages = version.getAllLanguagesForStringValues();
  for (const lang of languages.length ? languages : [{ lang: 1033, codepage: 1200 }]) version.setStringValues(lang, strings);
  version.outputToResourceEntries(resources.entries);

  resources.outputResource(executable);
  fs.writeFileSync(exe, Buffer.from(executable.generate()));
  console.log(`  • PhotoS: set icon and name on ${path.basename(exe)}`);
};

// macOS: give the app a local (ad-hoc) signature over its final contents. Apple Silicon refuses to run apps whose
// signature doesn't match, and Electron's original signature no longer does once PhotoS is packed in.
// Without an Apple Developer ID this is not notarized: first launch needs right-click → Open (see README).
function signMac(context) {
  const { execFileSync } = require('child_process');
  const app = path.join(context.appOutDir, `${context.packager.appInfo.productFilename}.app`);
  execFileSync('xattr', ['-cr', app]);
  execFileSync('codesign', ['--force', '--deep', '--sign', '-', '--identifier', context.packager.appInfo.id, app], { stdio: 'inherit' });
  execFileSync('codesign', ['--verify', '--deep', '--strict', app], { stdio: 'inherit' });
  console.log(`  • PhotoS: ad-hoc signed ${path.basename(app)}`);
}
