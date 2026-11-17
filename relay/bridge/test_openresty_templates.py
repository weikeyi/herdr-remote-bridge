"""Static safety checks; not a replacement for Docker/nginx/Caddy validation."""
from pathlib import Path
import unittest
import yaml

ROOT = Path(__file__).resolve().parent


class OpenRestyTemplates(unittest.TestCase):
    def load(self, mode):
        return yaml.safe_load((ROOT / f'compose.openresty-{mode}.yaml').read_text())

    def test_host_binds_only_loopback(self):
        doc = self.load('host')
        self.assertEqual(doc['services']['caddy']['ports'], ['127.0.0.1:18080:8080/tcp'])
        self.assertEqual(doc['services']['caddy']['networks'], ['backend'])

    def test_network_has_no_published_ports(self):
        doc = self.load('network')
        for service in doc['services'].values():
            self.assertNotIn('ports', service)
        self.assertTrue(doc['networks']['ingress']['external'])
        self.assertIn(':?', doc['networks']['ingress']['name'])
        self.assertEqual(doc['services']['caddy']['networks']['ingress']['aliases'], ['herdr-bridge-http'])

    def test_gateway_is_private_in_both_modes(self):
        for mode in ['host', 'network']:
            with self.subTest(mode=mode):
                doc = self.load(mode)
                gateway = doc['services']['gateway']
                self.assertNotIn('ports', gateway)
                self.assertEqual(gateway['networks'], ['backend'])
                self.assertTrue(doc['networks']['backend']['internal'])
                env = gateway['environment']
                self.assertEqual(env['HERDR_GATEWAY_STUN_ADDR'], '')
                self.assertIn(':?', env['HERDR_GATEWAY_REGISTRATION_TOKEN'])
                self.assertEqual(env['HERDR_GATEWAY_TRUSTED_PROXY'], 'true')
                for service in doc['services'].values():
                    self.assertNotIn('network_mode', service)
                    self.assertNotIn('privileged', service)

    def test_caddy_is_http_only_and_preserves_protection(self):
        text = (ROOT / 'Caddyfile.openresty').read_text()
        for value in ['auto_https off', 'admin off', ':8080 {', 'protocols h1 h2',
                      'Cache-Control "no-cache, no-store"', 'Referrer-Policy "no-referrer"',
                      'X-Content-Type-Options "nosniff"', 'X-Frame-Options "DENY"',
                      'root * /srv/phone', 'respond 404']:
            self.assertIn(value, text)
        self.assertLess(text.index('handle @probe'), text.index('handle @websocket'))
        self.assertEqual(text.count('reverse_proxy gateway:8443'), 2)
        self.assertEqual(text.count('header_up X-Forwarded-For {http.request.header.X-Forwarded-For}'), 2)
        self.assertEqual(text.count('header_up -Forwarded'), 2)
        self.assertEqual(text.count('header_up -X-Real-IP'), 2)
        self.assertNotIn('h3', text)

    def test_nginx_websocket_and_header_boundary(self):
        text = (ROOT / 'openresty-location.conf.example').read_text()
        for value in ['proxy_http_version 1.1;', 'proxy_set_header Upgrade $http_upgrade;',
                      'proxy_set_header Connection "upgrade";',
                      'proxy_set_header X-Forwarded-For $remote_addr;',
                      'proxy_set_header X-Real-IP "";', 'proxy_set_header Forwarded "";',
                      'proxy_set_header X-Forwarded-Proto https;', 'proxy_cache off;',
                      'proxy_buffering off;', 'proxy_read_timeout 3600s;',
                      'proxy_send_timeout 3600s;', 'access_log off;']:
            self.assertIn(value, text)
        self.assertNotIn('$proxy_add_x_forwarded_for', text)
        self.assertNotIn('proxy_set_header Origin', text)

    def test_verified_web_bundle_override(self):
        for name in ['compose.yaml', 'compose.openresty-host.yaml', 'compose.openresty-network.yaml']:
            doc = yaml.safe_load((ROOT / name).read_text())
            self.assertIn('${HERDR_PHONE_WEB_ROOT:-../../frontend/dist}:/srv/phone:ro',
                          doc['services']['caddy']['volumes'])

    def test_original_standalone_template_retained(self):
        original = yaml.safe_load((ROOT / 'compose.yaml').read_text())
        self.assertEqual(original['services']['caddy']['ports'], ['80:80/tcp', '443:443/tcp'])
        self.assertIn('{$HERDR_BRIDGE_DOMAIN}', (ROOT / 'Caddyfile').read_text())


if __name__ == '__main__':
    unittest.main(verbosity=2)
