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


    schema = [{":db/ident": ":person/name", 
               ":db/valueType": ":db.type/string",   
               ":db/cardinality": ":db.cardinality/one"}]

    request = json(schema)
    client.post('/transact', request, content_type='application/json', accept='application/json')
    
    response = parseEdn(client.post('/transact', """[[:db/add, "ID", :person/name, "Karen Spärck Jones"]]"""))
    id = response[":tempids"]["ID"]
    onlyWithJones = response[":db-after"]

    response = client.post("/pull", edn({':pattern': ['*'], 
                                         ':eid': id }))
    
    print("Found", parseEdn(response)[":ret"])

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
        {ident: ":person/id",
         valueType: string,
         cardinality: one,
         unique: identity,
         doc: "A person id is a unique string, by entity. All writes where this id is referred will be on that entity"},
        
        {ident: ":person/assignments",
         valueType: ref,
         cardinality: many,
         doc: "A persons assignments, are reference to many things"},
       
        {ident: ":assignment/id",
         valueType: string,
         cardinality: one,
         unique: identity,
         doc: "An assignment has an unique id by identity"},
       
        {ident: ":assignment/name", 
         valueType: string, 
         cardinality: one},
    ]


    
    print("\nTransact the schema:\n""", pformat(SCHEMA))
    request = json(SCHEMA)
    response = client.post('/transact', request, content_type='application/json', accept='application/json')
    schema_tx = parseJson(response)
    assert schema_tx[':tx-eid'] > 0
    print("The result:\n" + pformat(schema_tx))

    ada_id = "ada"
    notes_id = "notes"
    data = [{':person/name': "Ada Lovelace",
             ':person/id': ada_id,
             ':db/id': "entity-id",
             ':person/assignments': {
                    ':assignment/id': notes_id,
                    ':assignment/name': "Analytical notes"}}]

    print("\nAdd some data:\n" ,pformat(data))
    ##Lets add some basic data

    request = json(data)
    response = client.post('/transact', request, content_type='application/json', accept='application/json')
    data_tx = parseJson(response)
    print("The result:\n", pformat(data_tx))
    assert data_tx[':tx-eid'] > schema_tx[':tx-eid']

    pattern = Keyword('pattern')
    eid = Keyword('eid')
    data = {pattern: [':person/name',
                      {':person/assignments': [':assignment/name']}],
            eid: [':person/id', ada_id]}
    print("\nPull the data for person/id:", ada_id, "from db")
    print("with pull expression:\n", pformat(data[pattern]))
    print("for entity ", pformat(data[eid]))
    print("Note using extensible data notation ->")
    request = edn(data)
    response = parseJson( client.post('/pull', request, accept='application/json'))
    print("Result:\n", pformat(response))

    assert response[':ret'][':person/name'] == "Ada Lovelace"
    assignment = response[':ret'][':person/assignments'][0]
    assert assignment[':assignment/name'] == "Analytical notes"

    print("\nLets update the schema, from now on, as edn string")
    request = """[{:db/ident :assignment/days
                   :db/valueType :db.type/long
                   :db/cardinality :db.cardinality/one
                   :db/doc "Number of days for assignment"}]"""

    response = client.post('/transact', request, accept='application/edn')

    print("\nAnd add data to Adas assignment id:" + notes_id)
    client.post('/transact', json([{':assignment/id': notes_id, ':assignment/days': 12}]), content_type='application/json', accept='application/json')

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
    response = parseJson( client.post('/pull', request, accept='application/json'))
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
    response = client.post('/q', request, content_type='application/json', accept='application/json')
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
    
    print(pformat(tx))
    
    request = json(tx)
    response = client.post('/transact', request, content_type='application/json', accept='application/json')
    batch_tx = parseJson(response)
    assert batch_tx[':tx-eid'] > data_tx[':tx-eid']

    find = Keyword('find') # :find
    where = Keyword('where') # :where

    print("Lets find all names. Note that response is streaming as chunked")
    request = edn([find, '?name', where, ['?person', person_name, '?name']])
    streamed_response = client.post('/q', request)
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
                ['?assignment', assignment_name, '?assignment_name']],
               ":inputs" :["Grace Hopper"]}


    response = parseEdn(client.post('/q', edn(request)))
    print("Got:", response[ret], "from", response[':t'] )

    request[":inputs"] = ["Grace Hopper"]
    print("Let's pull within the query")

    pull = Symbol("pull")
    all = Symbol("*")

    request = [
        find, (pull, '?person', [all, {assignments: [all]}]),
        _in, defaultDb,
        where,
          ['?person', person_name]]
       
    
    response = parseEdn(client.post('/q', edn(request)))
    print(pformat(response)) 

    response = parseEdn(client.post('/q', edn({":query": request, ":inputs": [onlyWithJones]})))
    print(pformat(response)) 

    print("\nWhat is the internal id of :person/id" + ada_id)
    person_id_request = json({':ident': [':person/id', ada_id]})
    person_id_response = client.post('/entid', person_id_request, content_type='application/json', accept='application/json')
    person_id = parseJson(person_id_response)
    assert isinstance(person_id[':ret'], int)

    print("\nThe internal :person/id is also an entity")

    attr_id = parseEdn(client.post('/entid', edn({':ident': ':person/id'})))


    request = edn({':eid': attr_id[ret]})
    attr_ident = parseEdn(client.post('/ident', request))
    assert attr_ident[':ret'] == ':person/id'

    print("\nSync until database reaches a certain state, useful in distributed apps")
    request = edn({':t': batch_tx[':db-after']})
    synced = parseEdn(client.post('/sync', request))
    assert synced[':ret'] >= batch_tx[':db-after']

    print("Get all tx from", schema_tx[':tx-eid'])
    tx_range_request = edn({':from': schema_tx[':tx-eid']})
    tx_range_response = client.post('/tx-range', tx_range_request)
    tx_range = parseEdn(tx_range_response)
    assert any(
        datom[2] == "Grace Hopper"
        for transaction in tx_range[ret]
        for datom in transaction[':data']
    )
    print(pformat(tx_range))


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
