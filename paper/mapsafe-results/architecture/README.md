# MapSafe NextGIS Mobile architecture

The manuscript now uses
`mapsafe_nextgis_mobile_architecture_2026-09-08.png`. Regenerate that image with
`render_mapsafe_architecture_20260908.py` after changing the implemented
workflow. The earlier `mapsafe_nextgis_mobile_architecture.drawio` and preview
are retained as editable historical sources for the pre-wallet architecture.

The file contains two pages:

1. **MapSafe NextGIS Mobile Architecture** - the complete owner-device,
   NextGIS/external-service, and recipient-device workflow.
2. **Public-key Exchange Detail** - NextGIS authentication-group discovery,
   member-owned key buckets, validation, out-of-band fingerprint confirmation,
   local pinning, and quarantine states.

Connector meanings in the current manuscript figure:

- solid arrows: implemented protected-data and community-resource flow;
- green dashed arrows: implemented NextGIS identity and public-key exchange;
- purple dashed arrows: local key and configuration use;
- teal dashed arrows: implemented external-wallet submission and EVM
  verification.

The current figure records the implemented filename-bound notarisation flow;
the old draw.io overview still labels blockchain functions as planned and must
not be substituted into the revised manuscript.
