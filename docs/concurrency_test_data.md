# Concurrency Test Data Template

## Environment
- Service version:
- Date:
- DB:
- RabbitMQ:
- Test machine:

## Anti-Oversell Test
- Dish ID:
- Initial stock_available:
- Initial stock_locked:
- Concurrent requests:
- Success count:
- Failed count:
- Expected:
  - success count <= initial stock_available
  - no negative stock
- Evidence SQL:
```sql
select id, stock_available, stock_locked, version from dish where id = ?;
```

## Duplicate Callback Idempotency Test
- Order number:
- Concurrent callbacks:
- Callback success count:
- Callback failed count:
- Expected:
  - pay_transaction for order_no = 1
  - outbox ORDER_PAID for biz_key = 1
- Evidence SQL:
```sql
select count(*) from pay_transaction where order_no = ?;
select count(*) from outbox_message where biz_key = ? and event_type = 'ORDER_PAID';
```

## Sample Result (Fill In)
| test_case | concurrency | success | failed | key verification |
|---|---:|---:|---:|---|
| anti_oversell |  |  |  | stock not negative |
| duplicate_callback |  |  |  | pay_transaction=1, ORDER_PAID=1 |

