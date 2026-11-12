-- A Studio tenant schema as the inbox sees it on DIGIT 3.0: (part of) public-service's application
-- table and the view the inbox reads. {schema} is replaced per tenant.
-- The view is a copy of public-service-init's V20261112__inbox_application_view.sql (DIGIT-Studio).
CREATE SCHEMA "{schema}";
CREATE TABLE "{schema}".application (
    application_number VARCHAR(255), module VARCHAR(255), business_service VARCHAR(255), status VARCHAR(255),
    workflow_status VARCHAR(255), additional_details JSONB, workflow JSONB,
    created_at TIMESTAMP, updated_at TIMESTAMP,
    tenant_id VARCHAR(255), service_code VARCHAR(255), createdby VARCHAR(255), last_modifiedby VARCHAR(255));
CREATE VIEW "{schema}".inbox_application AS
SELECT a.application_number,
       a.module,
       a.business_service,
       a.service_code,
       a.status,
       a.workflow_status,
       w.assignees,
       a.created_at,
       a.updated_at,
       jsonb_build_object(
           'tenantId', a.tenant_id,
           'applicationNumber', a.application_number,
           'module', a.module,
           'businessService', a.business_service,
           'serviceCode', a.service_code,
           'status', a.status,
           'additionalDetails', a.additional_details,
           'auditDetails', jsonb_build_object(
               'createdBy', a.createdby,
               'lastModifiedBy', a.last_modifiedby,
               'createdTime', (extract(epoch FROM a.created_at) * 1000)::bigint,
               'lastModifiedTime', (extract(epoch FROM a.updated_at) * 1000)::bigint),
           'currentProcessInstance', jsonb_build_object(
               'businessService', a.workflow ->> 'businessService',
               'state', jsonb_build_object('uuid', a.workflow_status, 'state', a.workflow_status),
               'assignes', (SELECT COALESCE(jsonb_agg(jsonb_build_object('uuid', x)), '[]'::jsonb)
                            FROM jsonb_array_elements_text(w.assignees) x))
       ) AS data
FROM "{schema}".application a
CROSS JOIN LATERAL (SELECT CASE WHEN jsonb_typeof(a.workflow -> 'assignees') = 'array'
                                THEN a.workflow -> 'assignees' ELSE '[]'::jsonb END AS assignees) w;
GRANT USAGE ON SCHEMA "{schema}" TO inbox_reader;
GRANT SELECT ON "{schema}".inbox_application TO inbox_reader;
