# Proposed factual Bold Keys policy additions — for owner review

These paragraphs are proposed website text only. No website file was edited or deployed. They describe the local 0.6.2 review implementation; the owner should reconcile them with the rest of the policy and third-party terms before publication.

> Bold Keys processes typing, dictionary corrections and optional context-model inference on your device. The keyboard does not upload your typed text to a remote inference service. Learned word/count lists, keyboard preferences, shortcuts, model-selection information and emoji history are stored in the app's private storage. The app does not add its own file-encryption layer. Android's device encryption and transfer behavior are platform features.
>
> When the app containing a text field asks the keyboard not to perform personalized learning, Bold Keys does not add words to its learned-word lists or update emoji history or emoji tone preferences from that field. Password, email-address and web-address fields also do not update those records. These protections depend on the containing app reporting its editor type and flags correctly. They do not control how that containing app handles text you enter.
>
> Context models are optional and run on your device. If you tap Download, Bold Keys requests the selected model from Hugging Face over HTTPS and may be redirected to a delivery provider. Typed text is not included in that request. The receiving services can observe ordinary network/request information such as your IP address, request time, HTTP headers and the requested model path. Their handling of that information is governed by their own policies. Bold Keys checks a downloaded model against its expected size and SHA-256 before keeping it. The default Wi-Fi-only option uses Android's metered-network status.
>
> AndroidX emoji support may request an emoji font from a system font provider installed on your device. That provider can use cached data or make network requests depending on the device and provider. Bold Keys does not supply typed text in its font request. Provider-side downloads and information handling are governed by the provider's behavior and policies.
>
> Opening a configured shortcut or the privacy-policy link passes that link or action to another app, such as a browser. The receiving app may use the network and handle the link contents under its own policy. Importing a model uses Android's document picker; a document provider you select may be backed by a cloud service. Bold Keys does not control those providers' network activity. Model weights and user-imported models have their own license and usage terms.
>
> The reviewed production build contains no analytics, advertising, billing or crash-upload SDK. Local processing does not mean that user-requested model downloads, system font providers, external apps or document providers are always network-free.

Review still required: current provider/CDN privacy policies and retention; Android/device-transfer behavior; final Play Data Safety classifications; the owner's support/contact and retention/deletion wording. This text is not a legal/export attestation or a claim that every third-party service has been exhaustively observed.
