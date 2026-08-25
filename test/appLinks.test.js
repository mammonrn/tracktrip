import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8');

/**
 * A config file with its comments stripped.
 *
 * Every assertion below is about what nginx will *do*, and this file explains
 * itself at length — without this, commenting a directive out leaves the
 * assertion passing on the text of its own explanation.
 */
const directivesOf = (p) =>
  read(p)
    .split('\n')
    .filter((line) => !line.trim().startsWith('#'))
    .join('\n');

/**
 * The three files that have to agree for an invite link to open the app.
 *
 * None of them is code that runs here — assetlinks.json is fetched by Android,
 * the nginx site runs on a server this repository never talks to, and join.html
 * is a page for a phone that does not have the app. What they have in common is
 * that every one of them fails *silently*: a wrong fingerprint, a redirect, a
 * missing content type and the link simply keeps opening a browser, which looks
 * exactly like the site not being finished yet.
 *
 * The Android side of the same agreement is checked in AppLinkTest.
 */

// The Digital Asset Links spec calls for "the uppercase SHA265 fingerprint of
// the certificate", written as colon-separated pairs of hex — 32 bytes, so 32
// pairs. Not lowercase and not unseparated.
// https://developers.google.com/digital-asset-links/v1/statements
const FINGERPRINT = /^[0-9A-F]{2}(:[0-9A-F]{2}){31}$/;

test('assetlinks.json is a valid statement list', () => {
  const statements = JSON.parse(read('deploy/assetlinks.json'));

  assert.ok(Array.isArray(statements), 'the statement list must be a JSON array');
  assert.equal(statements.length, 2);

  for (const statement of statements) {
    assert.deepEqual(statement.relation, ['delegate_permission/common.handle_all_urls']);
    assert.equal(statement.target.namespace, 'android_app');
    assert.ok(Array.isArray(statement.target.sha256_cert_fingerprints));
  }
});

test('both build variants are declared, release and debug', () => {
  // Verification is per package name *and* per certificate. The intent filter
  // is in the shared manifest, so the debug build claims the same links under
  // its own id — and a list with only the release id leaves every test build
  // falling back to a chooser.
  const statements = JSON.parse(read('deploy/assetlinks.json'));
  const packages = statements.map((s) => s.target.package_name).sort();

  assert.deepEqual(packages, ['app.ptrip.tracktrip', 'app.ptrip.tracktrip.debug']);
});

test('every fingerprint is uppercase, colon-separated, and 32 bytes long', () => {
  const statements = JSON.parse(read('deploy/assetlinks.json'));

  for (const statement of statements) {
    for (const fingerprint of statement.target.sha256_cert_fingerprints) {
      assert.match(fingerprint, FINGERPRINT, `${statement.target.package_name}: ${fingerprint}`);
      // A SHA-1 fingerprint is 20 bytes and looks close enough to be pasted in
      // by mistake — keystore-sha1.sh prints both, one line apart.
      assert.equal(fingerprint.split(':').length, 32);
    }
  }
});

test('no placeholder fingerprint survives', () => {
  // The file shipped with REPLACE_WITH_* until the real ones arrived. A
  // placeholder deployed by accident is not dangerous — verification just fails
  // and Android shows a chooser — but it fails quietly, which is worse.
  const raw = read('deploy/assetlinks.json');

  assert.ok(!/REPLACE|PLACEHOLDER|TODO|XXXX/i.test(raw), 'placeholder left in assetlinks.json');
});

test('the nginx site serves assetlinks.json as application/json from an exact path', () => {
  const conf = directivesOf('deploy/nginx-ptrip.app.conf');

  // `location =` is an exact match: nothing else can shadow it and it cannot
  // pick up a trailing-slash rewrite.
  assert.match(conf, /location = \/\.well-known\/assetlinks\.json \{/);

  const block = conf.slice(conf.indexOf('location = /.well-known/assetlinks.json'));
  const body = block.slice(0, block.indexOf('\n    }'));

  // Google's statement-list documentation requires this content type; served
  // as text/plain the file is treated as no statement list at all.
  assert.match(body, /default_type application\/json;/);
  // A missing file must 404 rather than falling through to the join page.
  assert.match(body, /try_files \$uri =404;/);
});

test('the nginx site redirects nothing', () => {
  // Android treats anything other than HTTP 200 as an error and does not
  // follow 301 or 302, so a redirect on this host does not break loudly:
  // verification simply never succeeds.
  const conf = directivesOf('deploy/nginx-ptrip.app.conf');

  assert.ok(!/return\s+30\d/.test(conf), 'a redirect was added to ptrip.app');
  assert.ok(!/\brewrite\s/.test(conf), 'a rewrite was added to ptrip.app');
});

test('the nginx site claims the apex and not www', () => {
  // Claiming www invites exactly the canonical-redirect rule that breaks
  // verification, and the app's intent filter names the apex.
  const conf = directivesOf('deploy/nginx-ptrip.app.conf');
  const names = conf.match(/^\s*server_name\s+(.*);/m);

  assert.ok(names, 'no server_name');
  assert.equal(names[1].trim(), 'ptrip.app');
});

test('the nginx site serves one page for every join code', () => {
  const conf = directivesOf('deploy/nginx-ptrip.app.conf');

  // `^~` so it wins over any regex location added later. One page for all
  // codes because this host has no database and cannot tell a real code from
  // a made-up one.
  assert.match(conf, /location \^~ \/join\/ \{/);
  assert.match(conf, /try_files \/join\.html =404;/);
});

test('the nginx site serves the privacy policy from an exact path', () => {
  const conf = directivesOf('deploy/nginx-ptrip.app.conf');

  // Google Play stores the policy URL and re-checks it, so this 404ing is a
  // listing problem rather than only a broken page — and it fails as quietly
  // as everything else on this host.
  //
  // An exact match for the same reason assetlinks.json uses one: nothing can
  // shadow it and it picks up no trailing-slash rewrite. Unlike /join/ there
  // is no variable part of the path, so there is nothing for a prefix to
  // absorb.
  assert.match(conf, /location = \/privacy\.html \{/);

  const block = conf.slice(conf.indexOf('location = /privacy.html'));
  const body = block.slice(0, block.indexOf('\n    }'));

  assert.match(body, /try_files \$uri =404;/);

  // No forced content type here, unlike assetlinks.json: that one overrides
  // mime.types because Google's statement-list parser rejects anything but
  // application/json. An HTML page served as application/json would download
  // instead of rendering, so this asserts the override was *not* copied over.
  assert.ok(!/default_type/.test(body), 'privacy.html must be left to mime.types');
});

test('the privacy policy page carries no external asset', () => {
  // Same rule as the invite page: no fonts, no scripts, no images, no CDN.
  // This one is also read by people deciding whether to trust the app with
  // their location, and a policy page that phones a third party to render is
  // a poor way to open that argument.
  const html = read('deploy/www/privacy.html');

  assert.ok(!/src=["']https?:/i.test(html), 'external script or image');
  assert.ok(!/<link\b[^>]*href=["']https?:/i.test(html), 'external stylesheet');
  assert.ok(!/@import/i.test(html), 'external stylesheet via @import');
});

test('the privacy policy page is a complete, self-describing document', () => {
  // It is the URL handed to Google Play, so the failure that matters is it
  // going up half-finished: a draft marker left in, or no way to get in touch,
  // which Play requires the policy to provide.
  const html = read('deploy/www/privacy.html');

  assert.match(html, /<title>/i, 'no title');
  assert.match(html, /<meta charset="utf-8">/i, 'no charset — Thai text would render as mojibake');
  assert.ok(!/REPLACE|PLACEHOLDER|TODO|LOREM|XXXX/i.test(html), 'placeholder left in privacy.html');
  assert.match(html, /mailto:/, 'no contact address — Play requires the policy to give one');
});

test('the nginx site serves the account-deletion page from an exact path', () => {
  const conf = directivesOf('deploy/nginx-ptrip.app.conf');

  // Play stores this URL separately from the policy one and re-fetches it, so
  // it fails the same quiet way: the listing breaks, not the site.
  assert.match(conf, /location = \/delete-account\.html \{/);

  const block = conf.slice(conf.indexOf('location = /delete-account.html'));
  const body = block.slice(0, block.indexOf('\n    }'));

  assert.match(body, /try_files \$uri =404;/);
  // Not copied from assetlinks.json — an HTML page forced to application/json
  // downloads instead of rendering.
  assert.ok(!/default_type/.test(body), 'delete-account.html must be left to mime.types');
});

test('the account-deletion page carries no external asset', () => {
  const html = read('deploy/www/delete-account.html');

  assert.ok(!/src=["']https?:/i.test(html), 'external script or image');
  assert.ok(!/<link\b[^>]*href=["']https?:/i.test(html), 'external stylesheet');
  assert.ok(!/@import/i.test(html), 'external stylesheet via @import');
});

test('the account-deletion page is a complete, self-describing document', () => {
  // The address is the whole mechanism here — deletion is requested by email,
  // so a page that lost its mailto: would leave no way to ask at all.
  const html = read('deploy/www/delete-account.html');

  assert.match(html, /<title>/i, 'no title');
  assert.match(html, /<meta charset="utf-8">/i, 'no charset — Thai text would render as mojibake');
  assert.ok(!/REPLACE|PLACEHOLDER|TODO|LOREM|XXXX/i.test(html), 'placeholder left in delete-account.html');
  assert.match(html, /mailto:/, 'no contact address — the request cannot be made without one');
});

test('the nginx site serves the download page from an exact path', () => {
  const conf = directivesOf('deploy/nginx-ptrip.app.conf');

  assert.match(conf, /location = \/download\.html \{/);

  const block = conf.slice(conf.indexOf('location = /download.html'));
  const body = block.slice(0, block.indexOf('\n    }'));

  assert.match(body, /try_files \$uri =404;/);
  // Not copied from assetlinks.json — forced to application/json this page
  // would download instead of render.
  assert.ok(!/default_type/.test(body), 'download.html must be left to mime.types');
});

test('the download page is also reachable without the .html', () => {
  const conf = directivesOf('deploy/nginx-ptrip.app.conf');

  // /download is the address that gets handed out, so it has to work — but
  // NOT as a redirect. This host redirects nothing (a 30x on assetlinks.json
  // fails App Link verification silently), so the bare path serves the file
  // where it stands. Verified against nginx 1.24: /download returns 200
  // text/html, byte-identical to /download.html, because try_files takes the
  // content type from the file it lands on rather than from the request URI.
  assert.match(conf, /location = \/download \{/);

  const block = conf.slice(conf.indexOf('location = /download {'));
  const body = block.slice(0, block.indexOf('\n    }'));

  assert.match(body, /try_files \/download\.html =404;/);
  assert.ok(!/return\s+30\d/.test(body), '/download must serve the file, not redirect to it');
});

test('the download page still offers both ways to get the app', () => {
  // Deliberately NOT checked for external assets, unlike the two pages above:
  // this one is a Claude Design export that unpacks itself, and it carries
  // preconnect hints to Google Fonts that those assertions would reject.
  //
  // What is worth pinning is the two links, because they are the entire point
  // of the page and the one thing a re-export can silently drop — everything
  // around them is regenerated markup nobody reads in a diff.
  const html = read('deploy/www/download.html');

  assert.match(html, /tinyurl\.com\/PTripsGG/, 'the Play Store link is gone');
  assert.match(html, /tinyurl\.com\/PTripsCR/, 'the direct APK link is gone');
});

test('the invite page reads the code out of the path', () => {
  // The page is served for every /join/CODE, so filling the code in is the
  // page's own job. Run its script the way a browser would, with a stand-in
  // for the two things it touches.
  const html = read('deploy/www/join.html');
  const script = html.slice(html.lastIndexOf('<script>') + 8, html.lastIndexOf('</script>'));

  const shown = (pathname) => {
    const elements = {
      code: { textContent: '', hidden: true },
      open: { href: '', hidden: true },
    };
    vm.runInNewContext(script, {
      location: { pathname },
      document: { getElementById: (id) => elements[id] },
      decodeURIComponent,
      encodeURIComponent,
    });
    return elements;
  };

  const good = shown('/join/K7M2QRX9');
  assert.equal(good.code.hidden, false);
  assert.equal(good.code.textContent, 'K7M2 QRX9');
  assert.equal(good.open.href, 'tracktrip://join?code=K7M2QRX9');
  assert.equal(good.open.hidden, false);

  // Lower case and a typed separator still resolve — somebody may have read
  // the code aloud rather than tapped the link.
  assert.equal(shown('/join/k7m2-qrx9').code.textContent, 'K7M2 QRX9');
  // A trailing slash is what a chat app or a browser may add.
  assert.equal(shown('/join/K7M2QRX9/').code.textContent, 'K7M2 QRX9');
});

test('the invite page prints nothing when the code is not one of ours', () => {
  // The page still explains itself; it just does not print a code that would
  // be wrong to type in. I, O, 0 and 1 are not in the server's alphabet, so a
  // code containing one was misread.
  const html = read('deploy/www/join.html');
  const script = html.slice(html.lastIndexOf('<script>') + 8, html.lastIndexOf('</script>'));

  const hidden = (pathname) => {
    const elements = {
      code: { textContent: '', hidden: true },
      open: { href: '', hidden: true },
    };
    vm.runInNewContext(script, {
      location: { pathname },
      document: { getElementById: (id) => elements[id] },
      decodeURIComponent,
      encodeURIComponent,
    });
    return elements.code.hidden && elements.open.hidden;
  };

  assert.ok(hidden('/join/'), 'no code at all');
  assert.ok(hidden('/join/K7M2QRX'), 'too short');
  assert.ok(hidden('/join/K7M2QRX99'), 'too long');
  assert.ok(hidden('/join/K7M2QRXO'), 'letter O is not in the alphabet');
  assert.ok(hidden('/join/K7M2QRX0'), 'digit 0 is not in the alphabet');
  assert.ok(hidden('/join/<script>alert(1)</script>'), 'not a code');
});

test('the invite page carries no external asset', () => {
  // It has to render on a phone with one bar of signal, and every external
  // request is one more thing that can fail. Also the reason the code is put
  // in with textContent rather than innerHTML.
  const html = read('deploy/www/join.html');

  assert.ok(!/src=["']https?:/i.test(html), 'external script or image');
  assert.ok(!/href=["']https?:/i.test(html), 'external stylesheet or link');
  assert.ok(!/innerHTML/.test(html), 'the code from the URL must not be written as HTML');
});
