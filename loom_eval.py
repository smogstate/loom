#!/usr/bin/env python3
"""Minimal nREPL client that evals Clojure code and returns the result."""
import socket, uuid, sys, re

def nrepl_eval(code, port=7888, timeout=30):
    s = socket.socket()
    s.settimeout(timeout)
    s.connect(('localhost', port))

    msg_id = str(uuid.uuid4())
    msg = f'd2:id{len(msg_id)}:{msg_id}2:op4:eval4:code{len(code)}:{code}e'
    s.sendall(msg.encode())

    buf = b''
    while True:
        try:
            data = s.recv(8192)
            if not data:
                break
            buf += data
            if b'l4:doneee' in buf or buf.rstrip().endswith(b'l4:doneee'):
                break
            # multiple status done patterns
            if b'6:statusl4:done' in buf:
                break
        except socket.timeout:
            break
    s.close()

    # extract value
    result_parts = []
    raw = buf.decode(errors='replace')
    pos = 0
    while True:
        m = re.search(r'5:value(\d+):', raw[pos:])
        if not m:
            break
        length = int(m.group(1))
        start = pos + m.end()
        result_parts.append(raw[start:start+length])
        pos = start + length

    # extract errors
    errors = []
    for m in re.finditer(r'3:err(\d+):', raw):
        length = int(m.group(1))
        start = m.end()
        errors.append(raw[start:start+length])
    for m in re.finditer(r'2:ex(\d+):', raw):
        length = int(m.group(1))
        start = m.end()
        errors.append(raw[start:start+length])

    if result_parts:
        result = '\n'.join(result_parts)
        # Strip :vector [...] fields to avoid walls of floats
        result = re.sub(r',?\s*:vector\s*\[[^\]]*\]', '', result)
        return result
    elif errors:
        return 'ERROR: ' + '\n'.join(errors)
    return raw

if __name__ == '__main__':
    code = sys.argv[1] if len(sys.argv) > 1 else '(+ 1 2)'
    print(nrepl_eval(code))
