-- For GuestbookResponse, for guestbook response at request or download:
ALTER TABLE guestbookresponse ADD COLUMN guestbookResponseWorkflowPoint character varying(30) default 'download';