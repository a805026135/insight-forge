"""Read-only release smoke checks; never submits orders/refunds.

Run on the ECS host after deployment:
  python3 deploy/check_release.py --report /path/outside/repository/smoke.json
Set SMOKE_BUYER_USERNAME, SMOKE_BUYER_PASSWORD, SMOKE_MERCHANT_USERNAME and
SMOKE_MERCHANT_PASSWORD in the execution environment; no credentials are bundled.
Reports contain only check names, status and duration, never tokens or API data.
"""
import argparse
import json
import os
from pathlib import Path
import re
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1')
    parser.add_argument('--report', required=True)
    args = parser.parse_args()
    names = ('SMOKE_BUYER_USERNAME', 'SMOKE_BUYER_PASSWORD',
             'SMOKE_MERCHANT_USERNAME', 'SMOKE_MERCHANT_PASSWORD')
    missing = [name for name in names if not os.getenv(name)]
    if missing:
        parser.error('Required environment variables: ' + ', '.join(missing))
    results = []

    def request(path, token=None, body=None):
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        req = Request(args.base_url.rstrip('/') + path, headers=headers,
                      data=None if body is None else json.dumps(body).encode())
        try:
            with urlopen(req, timeout=30) as response:
                return response.status, response.headers, response.read()
        except HTTPError as error:
            return error.code, error.headers, error.read()

    def check(name, fn):
        start = time.monotonic()
        try:
            data = fn()
            results.append({'name': name, 'passed': True,
                            'ms': round((time.monotonic() - start) * 1000)})
            print('PASS', name, flush=True)
            return data
        except Exception as error:
            # Do not include response bodies or exception messages (may contain data).
            results.append({'name': name, 'passed': False,
                            'errorType': type(error).__name__})
            print('FAIL', name, type(error).__name__, flush=True)

    def require(condition):
        if not condition:
            raise AssertionError('Unexpected response')

    def api(path, token=None, body=None):
        status, _, raw = request(path, token, body)
        data = json.loads(raw)
        require(status == 200 and data.get('code') == 0)
        return data['data']

    def health():
        status, _, raw = request('/actuator/health')
        require(status == 200 and json.loads(raw).get('status') == 'UP')

    def assets():
        status, _, raw = request('/')
        require(status == 200)
        paths = re.findall(r'(?:src|href)="(/assets/[^"]+)"', raw.decode())
        require(len(paths) >= 2)
        for path in paths:
            status, headers, raw = request(path)
            require(status == 200 and len(raw) > 50)
            mime = 'javascript' if path.endswith('.js') else 'css'
            require(mime in headers.get('Content-Type', ''))
        for route in ['/user/home', '/user/support', '/merchant/evaluation']:
            status, _, raw = request(route)
            require(status == 200 and '<div id="root"></div>' in raw.decode())

    def denied(path, token=None, body=None):
        status, _, raw = request(path, token, body)
        require(status in (401, 403) or json.loads(raw).get('code') in (401, 403))

    check('health', health)
    check('frontend_assets_mime_and_spa_routes', assets)
    check('unauthenticated_private_api', lambda: denied('/api/user/orders'))
    check('invalid_login_rejected', lambda: denied('/api/login', body={
        'username': 'release-negative-' + os.urandom(12).hex(),
        'password': os.urandom(24).hex()}))
    buyer = check('buyer_login', lambda: api('/api/login', body={
        'username': os.environ['SMOKE_BUYER_USERNAME'],
        'password': os.environ['SMOKE_BUYER_PASSWORD']}))
    merchant = check('merchant_login', lambda: api('/api/login', body={
        'username': os.environ['SMOKE_MERCHANT_USERNAME'],
        'password': os.environ['SMOKE_MERCHANT_PASSWORD']}))
    if buyer:
        token = buyer['token']
        products = check('product_list', lambda: api('/api/catalog/products?limit=3', token))
        if products:
            check('product_detail', lambda: api('/api/catalog/products/' + str(products[0]['id']), token))
        else:
            check('product_detail', lambda: require(False))
        for name, path in [
            ('activity_list', '/api/activity'), ('cart', '/api/user/cart'),
            ('buyer_orders', '/api/user/orders'), ('buyer_after_sale', '/api/buyer/after-sale'),
            ('buyer_tickets', '/api/buyer/support-ticket'), ('buyer_chat_history', '/api/buyer/chat/history'),
        ]:
            check(name, lambda p=path: api(p, token))
        check('buyer_cannot_access_merchant', lambda: denied('/api/merchant/products', token))
    if merchant:
        token = merchant['token']
        for name, path in [
            ('merchant_profile', '/api/merchant/profile'), ('merchant_products', '/api/merchant/products'),
            ('merchant_orders', '/api/merchant/orders'), ('merchant_after_sales', '/api/merchant/after-sales'),
            ('merchant_tickets', '/api/merchant/support-tickets'), ('merchant_overview', '/api/merchant/overview'),
            ('knowledge_base_stats', '/api/merchant/kb/stats'), ('agent_ops', '/api/dashboard/agent-ops'),
            ('prompt_versions', '/api/dashboard/prompt-versions'),
        ]:
            check(name, lambda p=path: api(p, token))
    report = {'time': time.strftime('%Y-%m-%dT%H:%M:%S%z'), 'checks': results,
              'passed': sum(r['passed'] for r in results), 'total': len(results)}
    Path(args.report).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print('SUMMARY', report['passed'], '/', report['total'])
    return 0 if report['passed'] == report['total'] == 24 else 1


if __name__ == '__main__':
    raise SystemExit(main())
