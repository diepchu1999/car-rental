-- PostgreSQL rollback toàn migration thất bại, không để bảng nhận số dở dang.
SELECT to_regclass('identity.identity_card_registration') IS NULL;
