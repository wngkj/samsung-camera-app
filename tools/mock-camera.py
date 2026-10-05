#!/usr/bin/env python3
"""Local protocol simulator, never a claim of real WB800F firmware compatibility.
Usage: python3 tools/mock-camera.py --host 0.0.0.0 --advertise 192.168.1.5
Then Android app: Manual connection -> http://192.168.1.5:8765/description.xml
For the stock Android emulator use --advertise 10.0.2.2.
"""
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path
from urllib.parse import urlparse, parse_qs
import argparse, html, time, xml.etree.ElementTree as ET

parser=argparse.ArgumentParser()
parser.add_argument('--host',default='127.0.0.1')
parser.add_argument('--advertise',default='10.0.2.2')
parser.add_argument('--port',type=int,default=8765)
parser.add_argument('--delay',type=float,default=0)
args=parser.parse_args()
base=f'http://{args.advertise}:{args.port}'
fixtures=Path(__file__).resolve().parent.parent/'tests/fixtures'
service='urn:schemas-upnp-org:service:ContentDirectory:1'
def photo(i):
    size=(fixtures/f'DEMO_{i}.jpg').stat().st_size
    small=(fixtures/f'THUMB_{i}.jpg').stat().st_size
    return f'''<item id="{i}" parentID="1"><dc:title>DEMO_{i}.jpg</dc:title><dc:date>2026-10-05T08:00:0{i}+00:00</dc:date>
<res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN" resolution="160x120" size="{small}">{base}/thumb/{i}</res>
<res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_LRG" resolution="800x600" size="{size}">{base}/photo/{i}</res></item>'''
class Handler(BaseHTTPRequestHandler):
    def respond(self,data,status=200,mime='text/xml; charset=utf-8'):
        data=data.encode() if isinstance(data,str) else data
        self.send_response(status); self.send_header('Content-Type',mime); self.send_header('Content-Length',str(len(data))); self.end_headers()
        try:
            if args.delay and '/photo/' in self.path:
                for offset in range(0,len(data),1024): self.wfile.write(data[offset:offset+1024]); self.wfile.flush(); time.sleep(args.delay)
            else: self.wfile.write(data)
        except (BrokenPipeError,ConnectionResetError): pass
    def do_GET(self):
        if self.path in ['/description.xml','/smp_6_']:
            return self.respond(f'''<root xmlns="urn:schemas-upnp-org:device-1-0"><URLBase>{base}/</URLBase><device>
<deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType><friendlyName>[Camera]WB800F SIMULATOR</friendlyName>
<manufacturer>Samsung protocol simulator (independent)</manufacturer><UDN>uuid:wb800f-local-simulator</UDN><modelName>SIMULATOR</modelName>
<serviceList><service><serviceType>{service}</serviceType><serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>
<controlURL>/control</controlURL><eventSubURL>/event</eventSubURL></service></serviceList></device></root>''')
        for route,prefix in [('/photo/','DEMO_'),('/thumb/','THUMB_')]:
            if self.path.startswith(route):
                i=self.path[len(route):]
                if i not in ['1','2','3']: return self.respond('missing',404,'text/plain')
                return self.respond((fixtures/(prefix+i+'.jpg')).read_bytes(),mime='image/jpeg')
        return self.respond('missing',404,'text/plain')
    def do_POST(self):
        body=self.rfile.read(int(self.headers.get('Content-Length','0')))
        root=ET.fromstring(body)
        def field(name):
            return next((e.text or '' for e in root.iter() if e.tag.split('}')[-1]==name),'')
        object_id=field('ObjectID'); start=int(field('StartingIndex') or 0)
        if object_id=='0': items=['<container id="1" parentID="0"><dc:title>Pictures</dc:title></container>']
        elif object_id=='1': items=[photo(i) for i in range(1,4)]
        else: items=[]
        # Return one entry per page, exercising pagination on every run.
        page=items[start:start+1]
        didl='<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/">'+''.join(page)+'</DIDL-Lite>'
        self.respond(f'''<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:BrowseResponse xmlns:u="{service}">
<Result>{html.escape(didl)}</Result><NumberReturned>{len(page)}</NumberReturned><TotalMatches>{len(items)}</TotalMatches><UpdateID>1</UpdateID>
</u:BrowseResponse></s:Body></s:Envelope>''')
    def do_SUBSCRIBE(self):
        self.send_response(200); self.send_header('SID','uuid:wb800f-mock-subscription'); self.send_header('TIMEOUT','Second-300'); self.send_header('Content-Length','0'); self.end_headers()
print(f'SIMULATOR, not a physical camera: {base}/description.xml',flush=True)
ThreadingHTTPServer((args.host,args.port),Handler).serve_forever()
