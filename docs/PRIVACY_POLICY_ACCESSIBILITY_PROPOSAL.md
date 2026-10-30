# Proposed live-policy additions — owner review, no deployment

Suggested factual wording:

“Bold Keys processes typing and optional context-model inference on your device. Password and email fields suppress correction and suggestions. Web-address fields are not automatically rewritten, but suggestions may appear. Editors requesting no personalized learning, and password/email/web-address fields, do not update learned words or emoji history/tone preferences. These protections depend on the editor reporting its input type and flags correctly.

“Enabled accessibility tools can navigate individual keyboard controls and the suggestions currently visible on screen. Bold Keys does not automatically announce editor contents or changing suggestions. Android, the host editor and the user's accessibility tool may provide their own editing feedback. Incognito prevents new learning; it does not hide every visible suggestion.

“If you choose a model download, your device contacts Hugging Face and its delivery providers over HTTPS; they can receive ordinary connection/request metadata such as IP address, request time and requested model. Bold Keys does not add typed text to that request. Android's emoji font provider may use cached data or networking. Browser/Speed-key links are handed to another app, and a document provider selected for model import may be cloud-backed. Those providers have their own network behavior and policies.”

This describes app behavior, not a guarantee about host apps, Android services or third-party retention, and is not a submitted Data Safety/export declaration. The existing optional-model terms still apply. No website edits or deployment are included.
