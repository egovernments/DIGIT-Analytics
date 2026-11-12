-- A Studio tenant schema as the inbox sees it on DIGIT 3.0: (part of) public-service's application
-- table and the view the inbox reads. {schema} is replaced per tenant.
CREATE SCHEMA "{schema}";
CREATE TABLE "{schema}".application (
    application_number VARCHAR(255), module VARCHAR(255), business_service VARCHAR(255), status VARCHAR(255),
    workflow_status VARCHAR(255), additional_details JSONB, workflow JSONB,
    created_at TIMESTAMP, updated_at TIMESTAMP);
CREATE VIEW "{schema}".inbox_application AS
SELECT a.application_number, a.module, a.business_service, a.workflow_status,
       COALESCE(a.workflow -> 'assignees', '[]'::jsonb) AS assignees,
       a.created_at, a.updated_at,
       jsonb_build_object(
           'applicationNumber', a.application_number,
           'module', a.module,
           'businessService', a.business_service,
           'status', a.status,
           'additionalDetails', a.additional_details,
           'auditDetails', jsonb_build_object(
               'createdTime', (extract(epoch FROM a.created_at) * 1000)::bigint,
               'lastModifiedTime', (extract(epoch FROM a.updated_at) * 1000)::bigint),
           'currentProcessInstance', jsonb_build_object(
               'businessService', a.workflow ->> 'businessService',
               'state', jsonb_build_object('uuid', a.workflow_status, 'state', a.workflow_status),
               'assignes', (SELECT COALESCE(jsonb_agg(jsonb_build_object('uuid', x)), '[]'::jsonb)
                            FROM jsonb_array_elements_text(COALESCE(a.workflow -> 'assignees', '[]'::jsonb)) x))
       ) AS data
FROM "{schema}".application a;
GRANT USAGE ON SCHEMA "{schema}" TO inbox_reader;
GRANT SELECT ON "{schema}".inbox_application TO inbox_reader;
