-- Chỉ dùng trong test để kiểm tra index do migration tạo.
-- Index phải hợp lệ, sẵn sàng nhận dữ liệu và dùng GiST trên location.
-- Không chấp nhận index biểu thức hoặc index chỉ bao phủ một phần bảng.
SELECT EXISTS (
    SELECT 1
    FROM pg_catalog.pg_index AS i
             JOIN pg_catalog.pg_class AS index_relation
                  ON index_relation.oid = i.indexrelid
             JOIN pg_catalog.pg_am AS access_method
                  ON access_method.oid = index_relation.relam
    WHERE i.indrelid = CAST('branch.branch' AS pg_catalog.regclass)
      AND index_relation.relname = 'idx_branch_location'
      AND access_method.amname = 'gist'
      AND i.indisvalid
      AND i.indisready
      AND i.indnkeyatts = 1
      AND i.indexprs IS NULL
      AND i.indpred IS NULL
      AND pg_catalog.pg_get_indexdef(
                  i.indexrelid,
                  1,
                  true
          ) = 'location'
) AS has_valid_location_index;