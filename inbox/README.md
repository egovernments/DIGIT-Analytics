# inbox

The inbox service is a aggregation service which aggregates data of municipal services and workflow based on given complex search criteria
and returns applications and workflow data in paginated manner. The service also returns the total count matching the search criteria.



### Service Dependencies


- workflow-v2
- user-service
- egov-searcher
- The Municipal service for which inbox configuration is added


### Swagger API Contract

Link to the swagger API contract [yaml](https://raw.githubusercontent.com/egovernments/municipal-services/master/docs/inbox.yml) and editor link like below


### Postman Collection
Link to the postman collection [here](https://www.getpostman.com/collections/5e9f36ddf4b34460287e)


## Service Details

The service aggregates data from workflow and configured municipal services, to provide data required for displaying on inbox screen


### API Details


`_search` : This API is used to search inbox application data based on the criteria provided


### DIGIT 3.0: caller from the gateway

With `inbox.platform=3.0` the tenant, user and roles come from the headers Kong sets after verifying
the token (`X-Tenant-ID`, `X-User-ID`, `X-JWT-realm-roles`); the body's tenant and `RequestInfo` user
are replaced with them. The inbox must therefore be reachable only through a Kong route carrying the
platform's plugin chain (`dynamic-jwt`, `keycloak-rbac`, `header-enrichment`), which overwrites those
headers with the token's values. Roles are read only from `X-JWT-realm-roles`, never from `X-Roles`.

### Postgres store: inbox_item indexes

The `inbox_item` table (one per tenant schema) is indexed for searches that always give `module` and
`businessService`: by state for counts, and newest first for pages. A configuration that searches on
`attributes.<key>` or uses the `WILDCARD` operator has no index for it; the product adds one for its
keys in each tenant schema, for example
`CREATE INDEX ON inbox_item ((attributes ->> 'serviceCode'))`, or a `pg_trgm` GIN index for `WILDCARD`.


### Reference Document
TBD



