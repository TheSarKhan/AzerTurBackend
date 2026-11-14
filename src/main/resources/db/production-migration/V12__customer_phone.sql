-- Contact number the office uses to reach the customer. Older accounts have none until they add it.
ALTER TABLE customers ADD COLUMN phone varchar(24);
