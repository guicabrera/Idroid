---
tags:
  - idroid
  - api
created: YYYY-MM-DD
modified: YYYY-MM-DD
status: draft
---

# [API or service name]

> **Purpose:** [What this API or service provides and who should use it.]

## Overview

- **Base URL:** `[base-url]`
- **Version:** `[version]`
- **Authentication:** [Authentication scheme or none]
- **Format:** [For example, JSON over HTTPS]

## Authentication and authorization

Describe how callers authenticate and what permissions are required. Use placeholders only; never include real credentials.

## Endpoints

### `[HTTP method] [path]`

Describe the endpoint's behavior and relevant constraints.

**Authentication required:** [Yes/No]

#### Request

| Name | Location | Type | Required | Description |
|------|----------|------|----------|-------------|
| `[name]` | [path/query/header/body] | [type] | [Yes/No] | [Meaning and constraints] |

```http
[METHOD] [path] HTTP/1.1
Content-Type: application/json
Authorization: Bearer <token>
```

```json
{
  "field": "example"
}
```

#### Responses

| Status | Meaning |
|--------|---------|
| `[2xx]` | [Successful outcome] |
| `[4xx/5xx]` | [Error condition] |

Example success response:

```json
{
  "data": {}
}
```

Example error response:

```json
{
  "error": {
    "code": "[ERROR_CODE]",
    "message": "[Human-readable description]"
  }
}
```

## Errors

| Error code | HTTP status | Cause | Resolution |
|------------|-------------|-------|------------|
| `[ERROR_CODE]` | `[status]` | [Cause] | [Action] |

## Operational notes

- Rate limits: [Limits or not applicable]
- Idempotency and retries: [Behavior]
- Pagination: [Behavior or not applicable]
- Versioning and deprecation: [Policy]

## Related documentation

- [Authentication guide](../link-to-document.md)
- [Related API](../link-to-document.md)
- [External reference](https://example.com)
