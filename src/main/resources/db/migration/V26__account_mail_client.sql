-- Account emails link to the website for customers who signed up there and to the app for the others.
ALTER TABLE account_mail_jobs
 ADD COLUMN client varchar(4) NOT NULL DEFAULT 'app' CHECK (client IN ('app','web'));
