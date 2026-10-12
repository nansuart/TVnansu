"""One-off check of Wikidata ids and TVmaze matching. Writes tools/out/report.json."""
import json, time, urllib.parse, urllib.request, os

UA = "nansuTV/1.0 (personal player; https://github.com/nansuart/TVnansu)"

def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=90) as r:
        return json.loads(r.read().decode("utf-8"))

def sparql(q):
    return get("https://query.wikidata.org/sparql?format=json&query=" + urllib.parse.quote(q))["results"]["bindings"]

directors = ["Christopher Nolan","Denis Villeneuve","Quentin Tarantino","Martin Scorsese","Steven Spielberg",
    "Hayao Miyazaki","Guillermo del Toro","Alfonso Cuarón","Alejandro González Iñárritu","Pedro Almodóvar",
    "Wes Anderson","David Fincher","Ridley Scott","Stanley Kubrick","Tim Burton","Greta Gerwig","Bong Joon-ho",
    "Sofia Coppola","Francis Ford Coppola","Alfred Hitchcock","James Cameron","Peter Jackson","Alejandro Amenábar",
    "Juan Antonio Bayona","Clint Eastwood","Jordan Peele","Yorgos Lanthimos","Paul Thomas Anderson"]
studios = ["Studio Ghibli","A24","Pixar","Walt Disney Animation Studios","Marvel Studios","Lucasfilm",
    "DreamWorks Animation","Blumhouse Productions","Laika","Aardman Animations","Neon","Illumination",
    "Legendary Pictures","Searchlight Pictures","Toho","Sony Pictures Animation","Amblin Entertainment"]

report = {"directors": {}, "studios": {}, "qids": {}, "collections": {}, "tvmaze": {}}

for name in directors:
    q = 'SELECT ?i (COUNT(?f) AS ?n) WHERE { ?i rdfs:label "%s"@en . ?f wdt:P57 ?i } GROUP BY ?i ORDER BY DESC(?n) LIMIT 3' % name
    rows = sparql(q)
    report["directors"][name] = [(r["i"]["value"].rsplit("/",1)[1], int(r["n"]["value"])) for r in rows]
    time.sleep(1)

for name in studios:
    q = 'SELECT ?i (COUNT(?f) AS ?n) WHERE { ?i rdfs:label "%s"@en . ?f wdt:P272|wdt:P750 ?i } GROUP BY ?i ORDER BY DESC(?n) LIMIT 3' % name
    rows = sparql(q)
    report["studios"][name] = [(r["i"]["value"].rsplit("/",1)[1], int(r["n"]["value"])) for r in rows]
    time.sleep(1)

for qid in ["Q200092","Q1257444","Q102427","Q11424","Q202866","Q29168811","Q506240","Q24869","Q2526255"]:
    rows = sparql('SELECT ?es ?en WHERE { OPTIONAL { wd:%s rdfs:label ?es FILTER(LANG(?es)="es") } OPTIONAL { wd:%s rdfs:label ?en FILTER(LANG(?en)="en") } }' % (qid, qid))
    report["qids"][qid] = [(r.get("es",{}).get("value"), r.get("en",{}).get("value")) for r in rows]
    time.sleep(1)

def app_query(props, qid, popular):
    union = " UNION ".join("{ ?item wdt:%s wd:%s }" % (p, qid) for p in props)
    pop = "?item wikibase:sitelinks ?links . FILTER(?links >= 15)" if popular else ""
    return ("SELECT ?item ?es ?en ?date WHERE { "
            "VALUES ?type { wd:Q11424 wd:Q202866 wd:Q29168811 wd:Q506240 wd:Q24869 } "
            "?item wdt:P31 ?type . " + union + " " + pop + " "
            'OPTIONAL { ?item rdfs:label ?es FILTER(LANG(?es) = "es") } '
            'OPTIONAL { ?item rdfs:label ?en FILTER(LANG(?en) = "en") } '
            "OPTIONAL { ?item wdt:P577 ?date } }")

tests = {"ghibli": (["P272","P750"], None, "Studio Ghibli"), "nolan": (["P57"], None, "Christopher Nolan"),
         "a24": (["P272","P750"], None, "A24"), "halloween": (["P136"], "Q200092", None),
         "navidad": (["P136"], "Q1257444", None), "oscar": (["P166"], "Q102427", None)}
for key, (props, qid, label) in tests.items():
    if qid is None:
        src = report["directors"].get(label) or report["studios"].get(label)
        qid = src[0][0] if src else None
    if not qid:
        continue
    t = time.time()
    try:
        rows = sparql(app_query(props, qid, key in ("halloween", "navidad")))
        items = {}
        for r in rows:
            items.setdefault(r["item"]["value"], (r.get("es",{}).get("value"), r.get("en",{}).get("value")))
        report["collections"][key] = {"qid": qid, "rows": len(rows), "items": len(items),
                                      "seconds": round(time.time()-t, 1), "sample": list(items.values())[:8]}
    except Exception as e:
        report["collections"][key] = {"qid": qid, "error": str(e)}
    time.sleep(2)

for title in ["La casa de papel","Juego de tronos","Los Soprano","El juego del calamar","Stranger Things",
              "The Bear","Severance","Separación","The Last of Us","Arcane","Shogun","Rebelde"]:
    try:
        d = get("https://api.tvmaze.com/singlesearch/shows?q=" + urllib.parse.quote(title))
        report["tvmaze"][title] = {"name": d.get("name"), "premiered": d.get("premiered"),
            "network": (d.get("network") or {}).get("name"), "web": (d.get("webChannel") or {}).get("name")}
    except Exception as e:
        report["tvmaze"][title] = {"error": str(e)}
    time.sleep(0.6)

os.makedirs("tools/out", exist_ok=True)
json.dump(report, open("tools/out/report.json", "w"), ensure_ascii=False, indent=1)
print(json.dumps(report, ensure_ascii=False)[:3000])
