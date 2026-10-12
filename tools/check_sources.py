"""Second check: resolve remaining ids and compare film-type filters."""
import json, time, urllib.parse, urllib.request, os
UA = "nansuTV/1.0 (personal player; https://github.com/nansuart/TVnansu)"
ERRORS = []
def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=90) as r:
        return json.loads(r.read().decode("utf-8"))
def sparql(q):
    for a in range(3):
        try:
            return get("https://query.wikidata.org/sparql?format=json&query=" + urllib.parse.quote(q))["results"]["bindings"]
        except Exception as e:
            ERRORS.append(f"{type(e).__name__}: {e} :: {q[:100]}"); time.sleep(5*(a+1))
    return []
report = {"labels": {}, "collections": {}}
rows = sparql('SELECT ?i ?l ?d WHERE { VALUES ?l {"Christmas film"@en "J. A. Bayona"@en "Laika"@en "anime film"@en "superhero film"@en "Halloween film"@en} ?i rdfs:label ?l . OPTIONAL { ?i schema:description ?d FILTER(LANG(?d)="en") } }')
for r in rows:
    report["labels"].setdefault(r["l"]["value"], []).append((r["i"]["value"].rsplit("/",1)[1], r.get("d",{}).get("value")))
for name in ["J. A. Bayona", "Laika"]:
    prop = "wdt:P57" if "Bayona" in name else "wdt:P272|wdt:P750"
    rr = sparql('SELECT ?i (COUNT(?f) AS ?n) (SAMPLE(?fl) AS ?s) WHERE { ?i rdfs:label "%s"@en . ?f %s ?i . OPTIONAL { ?f rdfs:label ?fl FILTER(LANG(?fl)="en") } } GROUP BY ?i ORDER BY DESC(?n)' % (name, prop))
    report["labels"][name + " films"] = [(r["i"]["value"].rsplit("/",1)[1], r["n"]["value"], r.get("s",{}).get("value")) for r in rr]
    time.sleep(1)
def q_path(props, qid, popular):
    union = " UNION ".join("{ ?item wdt:%s wd:%s }" % (p, qid) for p in props)
    pop = "?item wikibase:sitelinks ?links . FILTER(?links >= 15)" if popular else ""
    return ("SELECT ?item ?es ?en ?date WHERE { " + union + " ?item wdt:P31/wdt:P279* wd:Q11424 . " + pop +
            ' OPTIONAL { ?item rdfs:label ?es FILTER(LANG(?es) = "es") } OPTIONAL { ?item rdfs:label ?en FILTER(LANG(?en) = "en") } OPTIONAL { ?item wdt:P577 ?date } }')
tests = {"ghibli": (["P272","P750"], "Q182950", False), "nolan": (["P57"], "Q25191", False), "a24": (["P272","P750"], "Q16248298", False),
         "halloween": (["P136"], "Q200092", True), "oscar": (["P166"], "Q102427", False)}
for key, (props, qid, pop) in tests.items():
    t = time.time(); rows = sparql(q_path(props, qid, pop))
    items = {}
    for r in rows: items.setdefault(r["item"]["value"], (r.get("es",{}).get("value"), r.get("en",{}).get("value")))
    report["collections"][key] = {"rows": len(rows), "items": len(items), "seconds": round(time.time()-t,1), "sample": list(items.values())[:12]}
    time.sleep(2)
report["errors"] = ERRORS
os.makedirs("tools/out", exist_ok=True)
json.dump(report, open("tools/out/report.json", "w"), ensure_ascii=False, indent=1)
