package org.egov.inbox.store;

import static org.egov.inbox.util.InboxConstants.SORT_BY_CONSTANT;
import static org.egov.inbox.util.InboxConstants.SORT_ORDER_CONSTANT;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.egov.inbox.web.model.V2.InboxQueryConfiguration;
import org.egov.inbox.web.model.V2.SearchParam;
import org.egov.inbox.web.model.workflow.ProcessInstanceSearchCriteria;
import org.egov.tracer.model.CustomException;
import org.springframework.util.ObjectUtils;

/**
 * SQL for an {@link InboxQuery} against a Postgres relation (a view or table). Column and relation
 * names come only from the module configuration and must be plain lower-case identifiers; every value
 * from the request is a bound parameter. A criterion the configuration does not allow is refused.
 *
 * <p>Operators: EQUAL ({@code =}, a list is {@code IN}), GTE / LTE, WILDCARD (case-sensitive
 * {@code LIKE '%value%'}, a list means every value must match, as in Elasticsearch), CONTAINS (a jsonb
 * array column holds the value; a list means any of them).
 *
 * <p>Times are epoch millis in the inbox. For a column listed in {@code timestampColumns} the bound
 * value is turned into a UTC timestamp ({@code to_timestamp(? / 1000.0) AT TIME ZONE 'UTC'}), so the
 * column is compared as it is and its index can be used.
 */
public class PostgresInboxQueryBuilder {

    /** SQL text and its parameters, in order. */
    public record Sql(String text, List<Object> params) {
    }

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*");
    private static final Pattern QUALIFIED_IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*(\\.[a-z_][a-z0-9_]*)?");
    private static final int DEFAULT_LIMIT = 10;
    private static final String MILLIS = "?";
    private static final String MILLIS_AS_UTC_TIMESTAMP = "(to_timestamp(? / 1000.0) AT TIME ZONE 'UTC')";

    private final boolean schemaPerTenant;

    /**
     * @param schemaPerTenant the relation is looked up in the request tenant's schema, so it must not
     *                        name a schema itself, and the tenant needs no column
     */
    public PostgresInboxQueryBuilder(boolean schemaPerTenant) {
        this.schemaPerTenant = schemaPerTenant;
    }

    public Sql search(InboxQuery query) {
        InboxQueryConfiguration config = query.config();
        if (config.getSortParam() == null) {
            throw configError(config, "sortBy is required");
        }
        List<Object> params = new ArrayList<>();
        String where = where(query, params);
        params.add(query.limit() == null ? DEFAULT_LIMIT : query.limit());
        params.add(query.offset() == null ? 0 : query.offset());
        return new Sql("SELECT " + column(config, config.getDocumentPath()) + "::text FROM " + relation(config)
                + where + " ORDER BY " + column(config, config.getSortParam().getPath()) + " " + query.sortOrder()
                + " LIMIT ? OFFSET ?", params);
    }

    public Sql count(InboxQuery query) {
        List<Object> params = new ArrayList<>();
        String where = where(query, params);
        return new Sql("SELECT count(*) FROM " + relation(query.config()) + where, params);
    }

    public Sql countByState(InboxQuery query) {
        List<Object> params = new ArrayList<>();
        String where = where(query, params);
        return new Sql("SELECT " + column(query.config(), query.config().getStatePath()) + "::text, count(*) FROM "
                + relation(query.config()) + where + " GROUP BY 1", params);
    }

    public Sql countModifiedBetween(InboxQuery query, long from, long to) {
        List<Object> params = new ArrayList<>();
        String where = where(query, params);
        params.add(from);
        params.add(to);
        String lastModified = column(query.config(), query.config().getLastModifiedPath());
        String value = isTimestamp(query.config(), lastModified) ? MILLIS_AS_UTC_TIMESTAMP : MILLIS;
        return new Sql("SELECT count(*) FROM " + relation(query.config()) + where + " AND "
                + lastModified + " BETWEEN " + value + " AND " + value, params);
    }

    private String where(InboxQuery query, List<Object> params) {
        InboxQueryConfiguration config = query.config();
        Map<String, SearchParam> allowed = new HashMap<>();
        if (config.getAllowedSearchCriteria() != null) {
            config.getAllowedSearchCriteria().forEach(p -> allowed.put(p.getName(), p));
        }

        List<String> conditions = new ArrayList<>();
        if (query.moduleCriteria() != null) {
            query.moduleCriteria().forEach((name, value) -> {
                if (name.equals(SORT_ORDER_CONSTANT) || name.equals(SORT_BY_CONSTANT)) {
                    return;
                }
                SearchParam param = allowed.get(name);
                if (param == null) {
                    throw new CustomException("INVALID_SEARCH_CRITERIA",
                            "Search criteria '" + name + "' is not allowed for module " + config.getModule());
                }
                condition(config, param, value, conditions, params);
            });
        }

        ProcessInstanceSearchCriteria process = query.processCriteria();
        if (process != null) {
            if (!schemaPerTenant) {
                processCondition(config, allowed, "tenantId", process.getTenantId(), conditions, params);
            }
            processCondition(config, allowed, "status", process.getStatus(), conditions, params);
            processCondition(config, allowed, "assignee", process.getAssignee(), conditions, params);
            processCondition(config, allowed, "fromDate", process.getFromDate(), conditions, params);
            processCondition(config, allowed, "toDate", process.getToDate(), conditions, params);
        }
        return conditions.isEmpty() ? " WHERE TRUE" : " WHERE " + String.join(" AND ", conditions);
    }

    /** A criterion the service filled in needs a column in the configuration when it has a value. */
    private void processCondition(InboxQueryConfiguration config, Map<String, SearchParam> allowed, String name,
                                  Object value, List<String> conditions, List<Object> params) {
        if (ObjectUtils.isEmpty(value)) {
            return;
        }
        SearchParam param = allowed.get(name);
        if (param == null) {
            throw configError(config, "allowedSearchCriteria needs '" + name + "' (a column) for the Postgres store");
        }
        condition(config, param, value, conditions, params);
    }

    private void condition(InboxQueryConfiguration config, SearchParam param, Object value,
                           List<String> conditions, List<Object> params) {
        if (value == null) {
            return;
        }
        String column = column(config, param.getPath());
        SearchParam.Operator operator = param.getOperator() == null ? SearchParam.Operator.EQUAL : param.getOperator();
        List<Object> values = value instanceof Collection<?> c ? new ArrayList<>(c) : null;
        boolean timestamp = isTimestamp(config, column);
        for (Object v : values == null ? List.of(value) : values) {
            checkScalar(param, v);
            if (timestamp && !(v instanceof Number)) {
                throw new CustomException("INVALID_SEARCH_CRITERIA", "Search criteria '" + param.getName() + "' takes epoch milliseconds");
            }
        }
        String placeholder = timestamp ? MILLIS_AS_UTC_TIMESTAMP : "?";

        switch (operator) {
            case EQUAL -> {
                if (values == null) {
                    conditions.add(column + " = " + placeholder);
                    params.add(value);
                } else if (values.isEmpty()) {
                    conditions.add("FALSE");
                } else {
                    conditions.add(column + " IN (" + String.join(", ", Collections.nCopies(values.size(), placeholder)) + ")");
                    params.addAll(values);
                }
            }
            case GTE, LTE -> {
                if (values != null) {
                    throw new CustomException("INVALID_SEARCH_CRITERIA", "Search criteria '" + param.getName() + "' takes one value");
                }
                conditions.add(column + (operator == SearchParam.Operator.GTE ? " >= " : " <= ") + placeholder);
                params.add(value);
            }
            case WILDCARD -> {
                if (timestamp) {
                    throw configError(config, "WILDCARD cannot be used on timestamp column " + column);
                }
                for (Object v : values == null ? List.of(value) : values) {
                    conditions.add(column + " LIKE ? ESCAPE '\\'");
                    params.add("%" + escapeLike(String.valueOf(v)) + "%");
                }
            }
            case CONTAINS -> {
                if (timestamp) {
                    throw configError(config, "CONTAINS cannot be used on timestamp column " + column);
                }
                if (values != null && values.isEmpty()) {
                    conditions.add("FALSE");
                    return;
                }
                List<String> any = new ArrayList<>();
                for (Object v : values == null ? List.of(value) : values) {
                    any.add(column + " @> jsonb_build_array(?::text)");
                    params.add(String.valueOf(v));
                }
                conditions.add(any.size() == 1 ? any.get(0) : "(" + String.join(" OR ", any) + ")");
            }
        }
    }

    private static void checkScalar(SearchParam param, Object value) {
        if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
            throw new CustomException("INVALID_SEARCH_CRITERIA",
                    "Search criteria '" + param.getName() + "' must be a string, number or boolean, or a list of them");
        }
    }

    private String relation(InboxQueryConfiguration config) {
        String relation = config.getIndex();
        Pattern rule = schemaPerTenant ? IDENTIFIER : QUALIFIED_IDENTIFIER;
        if (relation == null || !rule.matcher(relation).matches()) {
            throw configError(config, "index must be a lower-case relation name"
                    + (schemaPerTenant ? " without a schema (the tenant's schema is used)" : "") + ", got '" + relation + "'");
        }
        return relation;
    }

    private static String column(InboxQueryConfiguration config, String column) {
        if (column == null || !IDENTIFIER.matcher(column).matches()) {
            throw configError(config, "'" + column + "' is not a lower-case column name");
        }
        return column;
    }

    private static boolean isTimestamp(InboxQueryConfiguration config, String column) {
        List<String> timestampColumns = config.getTimestampColumns();
        if (timestampColumns == null) {
            return false;
        }
        timestampColumns.forEach(c -> column(config, c));
        return timestampColumns.contains(column);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static CustomException configError(InboxQueryConfiguration config, String message) {
        return new CustomException("INBOX_STORE_CONFIG_INVALID", "Inbox configuration of module " + config.getModule() + ": " + message);
    }
}
