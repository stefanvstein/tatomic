import sys
import urllib.parse
from pprint import pformat
from functools import reduce
from tutorial_http_tools import (
    Client,
    Keyword,
    LogicalVar,
    Symbol,
    edn,
    http_client,
    json,
    parseEdn,
    parseJson,
    tutorial_server,
)

def run_tutorial(base_url, raw_client):
    "Run the ordered HTTP examples and assert each demonstrated result."

    client = Client(base_url, raw_client)
   
    # Schema idents are a bit lengthy
    # https://docs.datomic.com/schema/schema-reference.html

    ident = ":db/ident"

    valueType = ":db/valueType"
    string = ":db.type/string"
    ref = ":db.type/ref"

    doc = ":db/doc"

    cardinality = ":db/cardinality"
    one = ":db.cardinality/one"
    many = ":db.cardinality/many"

    unique = ":db/unique"
    identity = ":db.unique/identity"


    # A schema as a data structure, not text.
    # A very simple model of person and their assignments

    SCHEMA = [
        {
            ident: ":person/id",
            valueType: string,
            cardinality: one,
            unique: identity,
            doc: "A person id is a unique string, by entity. All writes where this id is referred will be on that entity",
        },
        {ident: ":person/name", valueType: string, cardinality: one},
        {
            ident: ":person/assignments",
            valueType: ref,
            cardinality: many,
            doc: "A persons assignments, are reference to many things",
        },
        {
            ident: ":assignment/id",
            valueType: string,
            cardinality: one,
            unique: identity,
            doc: "An assignment has an unique id by identity",
        },
        {ident: ":assignment/name", valueType: string, cardinality: one},
    ]


    
    print("\nTransact the schema:""", pformat(SCHEMA))
    request = json(SCHEMA)
    ##Client will print the trafic on stdout
    response = client.post("/transact", request)
    schema_tx = parseJson(response)
    assert schema_tx[':tx-eid'] > 0
    print("The result:\n" + pformat(schema_tx))

    ada_id = "ada"
    notes_id = "notes"
    data = [{':person/id': ada_id,
             ':person/name': "Ada Lovelace",
             ':db/id': ada_id,
             ':person/assignments': {
                    ':assignment/id': notes_id,
                    ':assignment/name': "Analytical notes",
                    ':db/id': notes_id,}}]

    print("\nAdd some data:\n" ,pformat(data))
    ##Lets add some basic data

    request = json(data)
    response = client.post("/transact", request, content_type="application/json")
    data_tx = parseJson(response)
    print("The result:", pformat(data_tx))
    assert data_tx[':tx-eid'] > schema_tx[':tx-eid']

    data = {Keyword('pattern'): [Keyword('person/name'),
                         {Keyword('person/assignments'): [Keyword('assignment/name')]}],
            Keyword('eid'): [Keyword('person/id'), ada_id]}
    print("\nPull the data for person/id:", ada_id, "from db")
    print("with pull expression:", pformat(data))
    print("Note using extensible data notation ->")
    request = edn(data)
    response = parseJson( client.post("/pull", request, content_type="application/edn"))
    print("Result:\n", pformat(response))

    assert response[':ret'][':person/name'] == "Ada Lovelace"
    assignment = response[':ret'][':person/assignments'][0]
    assert assignment[':assignment/name'] == "Analytical notes"

    print("\nLets update the schema, from now on, as edn string")
    request = """[{:db/ident :assignment/days
                   :db/valueType :db.type/long
                   :db/cardinality :db.cardinality/one
                   :db/doc "Number of days for assignment"}]"""

    response = client.post(
        "/transact", request, content_type="application/edn"
    )

    print("\nAnd add data to Adas assignment id:" + notes_id)
    client.post(
      "/transact", json([{':assignment/id': notes_id,
                          ':assignment/days': 12}])   
    )

    pattern = Keyword('pattern')
    eid = Keyword('eid')
    person_name =Keyword('person/name')
    person_id = Keyword('person/id')
    assignments = Keyword('person/assignments')
    assignment_name = Keyword('assignment/name')
    assignment_days = Keyword('assignment/days')
    ret = ':ret'

    data = {pattern: [person_name,
                      {assignments: [assignment_name, 
                                     assignment_days]}],  
            eid: [person_id, ada_id]} 
    print("Lets look at entity:", ada_id, "with a pull:\n", pformat(data))
    request = edn(data)
    response = parseJson( client.post("/pull", request, content_type="application/edn"))
    assert response[ret][':person/name'] == "Ada Lovelace"
    assignment = response[ret][':person/assignments'][0]
    assert assignment[':assignment/name'] == "Analytical notes"
    assert assignment[':assignment/days'] == 12
    print("Got:", pformat(response))

    print("Lets do a query where we find all persons and their assignments:")

       # A query: find all person names and the name of the assigments associated with each.
    # JSON represents keywords with : and logical variables with ? in plain strings.

    QUERY = [
        ":find", "?name", "?assignment",
        ":where",
        ["?person", ":person/name", "?name"],
        ["?person", ":person/assignments", "?a"],
        ["?a", ":assignment/name", "?assignment"],
    ]
    print(pformat(QUERY))
    request = json(QUERY)
    response = client.post("/q", request, content_type="application/json")
    result = parseJson(response)
    print("Got:", pformat(result))
    assert ["Ada Lovelace", "Analytical notes"] in result[ret]

    print("\nLets add more people")

    # Let's make some data that can can be stored in the database.
    # Lets represent each entity as a dictionary.
    # Build enough related sample data to make query and streaming results useful.
    names = [   ("grace", "Grace Hopper", "compiler"),
                ("katherine", "Katherine Johnson", "orbital"),
                ("margaret", "Margaret Hamilton", "apollo"),
                ("annie", "Annie Easley", "rocket"),
                ("dorothy", "Dorothy Vaughan", "fortran"),
                ("mary", "Mary Jackson", "wind"),
            ]
    def person_tx(person_id, person_name, assignment_id):
        assignment_name = f"{person_name.split()[0]}'s work"
        return [
            {
                ":person/id": person_id,
                ":person/name": person_name,
                ":db/id": person_id,
            },
            {
                ":assignment/id": assignment_id,
                ":assignment/name": assignment_name,
                ":db/id": assignment_id,
            },
            {
                ":person/id": person_id,
                ":person/assignments": assignment_id,
            },
        ]
    def add_person(acc, person):
        person_id, person_name, assignment_id = person
        acc.extend(person_tx(person_id, person_name, assignment_id))
        return acc
    tx = reduce(add_person, names, [])
    
    
    
    request = json(tx)
    response = client.post("/transact", request, content_type="application/json")
    batch_tx = parseJson(response)
    assert batch_tx[':tx-eid'] > data_tx[':tx-eid']

    find = Keyword('find') # :find
    where = Keyword('where') # :where

    print("Lets find all names. Note that response is streaming as chunked")
    request = edn([find, '?name', where, ['?person', person_name, '?name']])
    streamed_response = client.post(
        "/q",
        request,
        content_type="application/edn",
        accept="application/edn")
    response = parseEdn(streamed_response)
    assert ["Grace Hopper"] in response[ret]
    assert ["Dorothy Vaughan"] in response[ret]
    print("They where found in database with t:", response[':t'])



    print("\nWe can find Grace using a parameter")
    _in = Keyword('in') # for input paramters
    defaultDb = Symbol('$') # default database
    request = {":query" :[find, '?name', '?assignment_name', 
                _in, defaultDb, '?name', 
                where, 
                ['?person', person_name, '?name'],
                ['?person', assignments, '?assignment'],
                ['?assignment', assignment_name, '?assignment_name'],
                ],
               ":inputs" :["Grace Hopper"]}
    #request = " {:query [:find  ?name :in $ ?name :where [?person :person/name ?name]" \
    #"[?person :person/assignments ?assignment]] :inputs [ \"Grace Hopper\"]}  "

    streamed_response = client.post(
            "/q",
            edn(request),
            content_type="application/edn",
            accept="application/edn")
    response = parseEdn(streamed_response)
    print("Got:", response[ret], "from", response[':t'] )

    print("Let's pull within the query")

    pull = Symbol("pull")
    all = Symbol("*")

    request = [
        find, (pull, '?person', [all, {assignments: [all]}]),
        where,
          ['?person', assignments]]
       
    
    streamed_response = client.post(
            "/q",
            edn(request),
            content_type="application/edn",
          )
    print(pformat(parseJson(streamed_response)))

    print("\nWhat is the internal id of :person/id" + ada_id)
    person_id_request = json({':ident': [':person/id', ada_id]})
    person_id_response = client.post("/entid", person_id_request, content_type="application/json")
    person_id = parseJson(person_id_response)
    assert isinstance(person_id[':ret'], int)

    print("\nThe internal :person/id is also an entity")

    attr_id_request = edn({':ident': ':person/id'})
    attr_id_response = client.post(
        "/entid",
        attr_id_request,
        content_type="application/edn",
        accept="application/edn")
    attr_id = parseEdn(attr_id_response)

    attr_ident_request = json({':eid': attr_id[ret]})
    attr_ident_response = client.post(
        "/ident", attr_ident_request, content_type="application/json"
    )
    attr_ident = parseJson(attr_ident_response)
    assert attr_ident[':ret'] == ':person/id'

    print("Sync until database reaches a certain state, useful in distributed apps")
    sync_request = json({':t': batch_tx[':db-after']})
    sync_response = client.post(
        "/sync", sync_request, content_type="application/json"
    )
    synced = parseJson(sync_response)
    assert synced[':ret'] >= batch_tx[':db-after']

    tx_t_response = client.get("/tx-to-t/%d" % batch_tx[':tx-eid'])
    tx_t = parseJson(tx_t_response)
    assert isinstance(tx_t, int)
    print("9. tx->t converted the transaction entity")

    tx_range_request = edn({Keyword('from'): schema_tx[':tx-eid']})
    tx_range_response = client.post(
        "/tx-range",
        tx_range_request,
        content_type="application/edn",
        accept="application/edn")
    tx_range = parseEdn(tx_range_response)
    assert any(
        datom[2] == "Grace Hopper"
        for transaction in tx_range[ret]
        for datom in transaction[':data']
    )
    print("10. tx-range streamed the transaction log with EDN")

    roundtrip_tx_response = client.get("/t-to-tx/%d" % tx_t)
    roundtrip_tx = parseJson(roundtrip_tx_response)
    assert roundtrip_tx == batch_tx[':tx-eid']
    print("11. t->tx converted the logical t back to the transaction entity")


def main():
    """Own the temporary server and connection for one complete tutorial run."""
    with tutorial_server() as base_url:
        parsed_url = urllib.parse.urlsplit(base_url)
        host = parsed_url.hostname
        port = parsed_url.port
        print(f"Using {base_url}")
        with http_client(host, port) as raw_client:
            run_tutorial(base_url, raw_client)
    print("HTTP tutorial completed")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"FAILED: {exc}", file=sys.stderr)
        raise
