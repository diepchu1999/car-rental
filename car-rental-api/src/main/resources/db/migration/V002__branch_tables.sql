-- Schema riêng của module branch theo ADR-0008.
CREATE SCHEMA branch;

-- Danh tính và vị trí chi nhánh trong phạm vi Task 4.
-- Vị trí phục vụ BR-003.
-- Khóa chính và mã nghiệp vụ theo database-guideline mục 2.
CREATE TABLE branch.branch (
                               id BIGINT GENERATED ALWAYS AS IDENTITY,
                               code TEXT NOT NULL,
                               location public.geography(Point, 4326) NOT NULL,

                               CONSTRAINT pk_branch
                                   PRIMARY KEY (id),

                               CONSTRAINT uq_branch_code
                                   UNIQUE (code),

                               CONSTRAINT chk_branch_code_format
                                   CHECK (code COLLATE "C" ~ '^CN-[A-Z0-9]{6}$'),

                               CONSTRAINT chk_branch_location_not_empty
                                   CHECK (NOT public.ST_IsEmpty(location::public.geometry))
);

-- Index không gian phục vụ truy vấn theo vị trí,
-- bao gồm ST_DWithin theo database-guideline mục 5.
CREATE INDEX idx_branch_location
    ON branch.branch USING gist (location);