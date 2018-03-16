#!/usr/bin/python3

import json
import sys
import socket

with socket.create_connection(('127.0.0.1', 8888)) as conn:
    i = 0
    connfile = conn.makefile()
    for line in sys.stdin:
        msg = json.dumps(dict(languageTag='en', utterance=line.strip(), req=i))
        print(msg)
        conn.send((msg + '\n').encode('utf-8'))
        result = connfile.readline()
        print(result)
        i += 1