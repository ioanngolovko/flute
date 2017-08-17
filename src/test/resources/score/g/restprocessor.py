#   coding=utf-8
from flute.mappingbuilder import mapping, filter
from ru.curs.flute.rest import FluteResponse
from java.lang import System

@mapping('/foo')
def foo(context, request):
    return {
        "foo": 1,
        "bar": 2
    }

@mapping('/params')
def params(context, request):
    response = FluteResponse()

    text = request.url
    status = None

    if request.params.size() != 0:
        text = text + "?"

        params = request.params
        theFirst = True

        for key in params.keySet():
            if theFirst != True:
                text = text + "&"
            text = text + key + "=" + params.get(key)
            theFirst = False

        status = 200
    else:
        text = "there are no params received"
        status = 422

    response.text = text
    response.status = status

    return response


@mapping('/jsonPost', "POST")
def postWithJsonBody(context, request):
    dto = request.body
    dto['numb'] = dto['numb'] + 1
    return dto

#TODO: Маппинги ниже нужно будет усложнить и передавать между ними актуальные request/response
@filter('/beforeTest')
def beforeFilter(context, request):
    System.out.println('before filter')
    return {
        "foo": 1,
        "bar": 2
    }

@mapping('/beforeTest')
def handlerForBeforeFilter(context, request):
    System.out.println('handler')
    return {
        "foo": 1,
        "bar": 2
    }