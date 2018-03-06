# How to use GPAlloc

Briefly, you call `GET /api/googleproject` to request a "clean" billing project, which will return something like this:

```json
{
  "projectName": "gpalloc-dev-master-ggukibs",
  "cromwellAuthBucketUrl": "cromwell-auth-gpalloc-dev-master-ggukibs"
}
```

You can give this info to the [project registration endpoint](https://rawls.dsde-dev.broadinstitute.org/#!/admin/recordProjectOwnership) of a Rawls; that Rawls will do its own setup and convince itself that it was the one who created the project. Once the endpoint returns (it'll take a few seconds to sync google groups) the project should behave completely normally.
 
When you're finished with your project, call `DELETE /api/googleproject/<projectName>` to return it to the pool.

If you don't return it to the pool within two hours, GPAlloc will assume you forgot to clean up after yourself and do it for you.

Note that GPAlloc's proxy only accepts users with email addresses ending in `@test.firecloud.org` or `@quality.firecloud.org`. Anyone else will be turned away.
 
The situation in FiaB-style auto-testing land is a little different in that workbench-libs does some of this for you. So let's talk about that.

## GPAlloc in FiaB auto-tests

`workbench-service-test` now has support for GPAlloc as of [v0.5](https://github.com/broadinstitute/workbench-libs/blob/develop/serviceTest/CHANGELOG.md#05) (link goes to changelog where you can get the hash).

To use it, simply replace your calls to `withBillingProject` with the new method `withCleanBillingProject`. This does all the work to acquire and release GPAlloc'd projects for you and should take much less time doing so. (If no GPAlloc'd projects are available, it falls back to creating a new one the old way.)

If this is your service's first usage of GPAlloc, you should also place this block inside the `fireCloud` stanza of your test's `application.conf.ctmpl`:

```
fireCloud {
  //baseUrl, orchApiUrl et al are here already...
  
  {{if eq $environment "qa"}}
  gpAllocApiUrl = "https://gpalloc-qa.dsp-techops.broadinstitute.org/api/"
  {{else}}
  gpAllocApiUrl = "https://gpalloc-dev.dsp-techops.broadinstitute.org/api/"
  {{end}}
}
```

See [Sam's testing conf](https://github.com/broadinstitute/firecloud-automated-testing/blob/master/configs/sam/application.conf.ctmpl#L31) for an example.

Previous iterations of this document said a lot of confusing things about super-Suites and such. This is no longer the case: `withCleanBillingProject` can now properly clean up after itself (thanks Matt B!).

## Running auto-tests locally using GPAlloc

You may get the following error in your logs:

```
13:03:58.057 [default-akka.actor.default-dispatcher-6] INFO  o.b.dsde.workbench.service.GPAlloc$ - retry-able operation failed: 3 retries remaining, retrying in 100 milliseconds
javax.net.ssl.SSLProtocolException: handshake alert:  unrecognized_name
    at sun.security.ssl.ClientHandshaker.handshakeAlert(ClientHandshaker.java:1446) ~[na:1.8.0_131]
    at sun.security.ssl.SSLEngineImpl.recvAlert(SSLEngineImpl.java:1791) ~[na:1.8.0_131]
```

This is [Java being jumpy about SSL](https://www.ibm.com/support/knowledgecenter/en/SSYKE2_8.0.0/com.ibm.java.security.component.80.doc/security-component/jsse2Docs/sni_extension.html); we turn it off in our apps but you're likely missing that flag in your `SBT_OPTS`. You can update it like this:

```
export SBT_OPTS="$SBT_OPTS -Djsse.enableSNIExtension=false -Dheadless=false"
```
