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
 
The situation in FiaB-style auto-testing land is a little different in that workbench-libs does some of this for you. So let's talk about that.

## GPAlloc in FiaB auto-tests

`workbench-service-test` now has support for GPAlloc as of `0.5-30b3ceb`.

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
