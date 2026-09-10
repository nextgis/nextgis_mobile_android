# North Whangārei mobile case-study dataset

The source archive is the public `north_whangarei-SHP.zip` artifact from
`sharmapn/geonode_datasovereignty`. It accompanies the fictional
`north-whangarei-infected-trees` example described by Sharma et al. (2024),
DOI `10.1111/tgis.13153`.

The reviewed archive contains 23 WGS84 point coordinates and no DBF attribute
fields. MapSafe preserves those coordinates exactly. The bundled mobile
GeoJSON adds explicitly synthetic attributes so that the NextGIS Mobile field
record, export, encryption, and decryption paths are exercised with meaningful
tabular content. Those attributes must not be represented as observations from
the earlier study.

Rebuild and validate the mobile asset from the repository root:

```powershell
python .\scripts\prepare_north_whangarei_sample.py
```

The command checks the source SHA-256, geometry type, CRS, record count, extent,
and archive members before replacing the generated GeoJSON and manifest.
