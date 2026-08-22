# openHAB Eight Sleep Binding

An [openHAB](https://www.openhab.org/) binding for [Eight Sleep](https://www.eightsleep.com/) smart
mattress covers (Pod series), integrating sleep tracking, temperature control, adjustable bases,
the Pod 5 pillow and alarms via the Eight Sleep cloud API.

See [`org.openhab.binding.eightsleep/README.md`](org.openhab.binding.eightsleep/README.md) for full documentation.

## Repository layout

| Path | Contents |
|------|----------|
| `org.openhab.binding.eightsleep/` | The binding bundle (sources, OH-INF, tests, docs) |
| `tools/capture_fixtures.py` | Standalone script that captures live API payloads into contract-test fixtures |
| `tools/fixtures/` | Captured live API responses (personal data scrubbed) the contract tests validate against |

## Building

Requires JDK 21 and Maven:

```bash
mvn verify -Deightsleep.fixtures=tools/fixtures
```

The OSGi bundle is written to `org.openhab.binding.eightsleep/target/` — drop it into your
openHAB `addons` folder to install.

## License

[Eclipse Public License 2.0](LICENSE)
