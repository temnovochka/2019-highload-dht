import bisect
import itertools
import random
import gzip
from collections import defaultdict


def make_ammo(method, url, headers, case, body):
    """ makes phantom ammo """
    # http request w/o entity body template
    req_template = (
        "%s %s HTTP/1.1\r\n"
        "%s\r\n"
        "\r\n"
    )

    # http request with entity body template
    req_template_w_entity_body = (
        "%s %s HTTP/1.1\r\n"
        "%s\r\n"
        "Content-Length: %d\r\n"
        "\r\n"
        "%s\r\n"
    )

    if not body:
        req = req_template % (method, url, headers)
    else:
        req = req_template_w_entity_body % (method, url, headers, len(body), body)

    # phantom ammo template
    ammo_template = (
        "%d %s\n"
        "%s"
    )

    return ammo_template % (len(req), case, req)


def get_headers():
    headers = "Host: hostname.com\r\n" + \
              "User-Agent: tank\r\n" + \
              "Accept: */*"
    return headers


def get_body():
    return 'abcdeabcdeabcdeabcdeabcdeabcdeabcdeabcdeabcdeabcdeabcdeabcde'


def unique_put(n):
    body = get_body()
    headers = get_headers()
    return [
        make_ammo('PUT', '/v0/entity?id={}'.format(i), headers, '', body)
        for i in range(n)
    ]


def not_unique_put(n):
    body = get_body()
    headers = get_headers()
    res = []
    for i in range(n):
        request = make_ammo('PUT', '/v0/entity?id={}'.format(i), headers, '', body)
        res.append(request)
        if i % 10 == 0:
            res.append(request)
    res = res[:n]
    random.shuffle(res)
    return res


def existing_get(n):
    headers = get_headers()
    return [
        make_ammo('GET', '/v0/entity?id={}'.format(int(random.uniform(0, n))), headers, '', None)
        for i in range(n)
    ]


def newest_get(n):
    headers = get_headers()
    keys = list(range(n))
    cumdist = list(itertools.accumulate(keys))
    return [
        make_ammo('GET', '/v0/entity?id={}'.format(keys[bisect.bisect(cumdist, random.random() * cumdist[-1])]), headers, '', None)
        for _ in range(n)
    ]


def get_put(n):
    GET = 'GET'
    PUT = 'PUT'

    headers = get_headers()
    requests = [GET, PUT]
    take_body = {
        PUT: get_body(),
        GET: None
    }
    keys = [i for i in range(n)]

    res = []
    for i in range(n):
        request = requests[int(i % 2)]
        body = take_body[request]

        if request == PUT:
            key = 'new_{}'.format(i)
            keys.append(key)
        else:
            key = random.choice(keys)

        res.append(make_ammo(request, '/v0/entity?id={}'.format(key), headers, '', body))

    return res


def save_data(data, generator_name):
    file_name = 'generated_files/{}.gz'.format(generator_name)
    result = (it.encode('utf-8') for it in data)
    with gzip.open(filename=file_name, mode='wb') as file:
        file.writelines(result)


def main():
    n = 10000
    generators = [
        unique_put,
        not_unique_put,
        existing_get,
        newest_get,
        get_put
    ]

    for gen in generators:
        result = gen(n)
        save_data(result, gen.__name__)


if __name__ == '__main__':
    main()
