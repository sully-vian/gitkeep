# XSS test

This note checks that raw HTML is escaped in the preview.
Until sanitizing is in place, opening its preview **runs scripts**.
Once it is fixed, the lines below show as plain text and nothing pops up.

<img src="x" onerror="alert('xss: img onerror')">

<script>alert('xss: script tag')</script>

<b>inline HTML</b> should not render bold.

[javascript link](javascript:alert('xss:%20link'))
