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
<Log in Bluemix>
sbt dist
cf push
```