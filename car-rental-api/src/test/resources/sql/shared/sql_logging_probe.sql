-- Kiểm tương thích SQL log: dữ liệu giả, giữ nguyên dấu xuống dòng.
SELECT CAST(:label AS text) AS label,
       CAST(:quantity AS integer) AS quantity,
       CAST(:optionalValue AS text) AS optional_value;
