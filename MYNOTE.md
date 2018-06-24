# My Note

## How to deploy on Heroku

```sh
$ git clone <this repo uri>
$ cd <this repo>
$ heroku create
$ git push heroku master
# Done !
# You can go to https://<app name>.herokuapp.com/
```

## How to deploy on IBM Cloud (Bluemix)

```sh
# Log in Bluemix
# (Change -a and -o properly)
cf login -a https://api.ng.bluemix.net -u nwtgck@gmail.com
# <Input password for Bluemix>
sbt dist
cf push
```

## Files with hard-code version

* [build.sbt](build.sbt) (This is natural)
