render "docker-compose.yml.ctmpl"
render "gpalloc.conf.ctmpl"
copy_file "site.conf"

copy_secret_from_path "secret/dsp/dsp-techops/prod/common/server.crt"
copy_secret_from_path "secret/dsp/dsp-techops/prod/common/server.key"
copy_secret_from_path "secret/dsde/dsp-techops/common/ca-bundle.crt", field = "chain"

copy_secret_from_path "secret/dsde/firecloud/#{$env}/common/billing-account.json", field = "private_key", output_file_name = "billing-account.pem"
